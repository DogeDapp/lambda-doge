package doge.compiler.closures

import doge.compiler.types.TypeSystem.{Function, Type}
import doge.compiler.types._

/**
 * This is a phase of the compiler that ensure all closures
 * have a raw method which they can be lifted from.
 *
 * i.e. any built-in function is automatically encoded
 * in a local method which can be lifted.
 *
 * This handles not just partially applied methods, it (TBD) handles closure syntax.
 *
 * In the future, this should also ensure that any legitimate closures
 * are lifted.
 */
object ClosureLift {

  def liftClosures(module: ModuleTyped): ModuleTyped = {
    ModuleTyped(module.name, module.definitions.flatMap(d => lift(d, module.name)))
  }


  /** Extractor to look for a reference to a closure, including the number of arguments required
    * to pass into the reference until reaching the closure/function object.
    */
  object MethodReferenceType {
    import TypeSystem._
    def unapply(id: IdReferenceTyped): Option[Type] = {
      id.tpe match {
        case Function(_,_) => Some(id.tpe)
        case _ => None
      }
    }
  }

  object FunctionArgCount {
    import TypeSystem._
    def unapply(tpe: Type): Option[Int] = tpe match {
      case Function(arg, result) =>
        def countHelper(tpe: Type, count: Int): Int =
          tpe match {
            case Function(arg, result) => countHelper(result, count + 1)
            case _ => count
          }
        Some(countHelper(tpe, 0))
      case _ => None
    }
  }

  /**
   * Captures any function application which is partial.
   */
  object PartialApplication {
    def unapply(ast: TypedAst): Option[(ApExprTyped)] = ast match {
      case ap@ApExprTyped(MethodReferenceType(FunctionArgCount(argCount)), args, _, _) if args.size < argCount => Some(ap)
      case _ => None
    }
  }

  object PartialApplicationMoreThanOneArg {
    def unapply(ast: TypedAst): Option[(ApExprTyped)] = ast match {
      case ap@ApExprTyped(MethodReferenceType(FunctionArgCount(argCount)), args, _, _) if args.size + 1 < argCount => Some(ap)
      case _ => None
    }
  }
  object IsBuiltIn {
    def unapply(ast: TypedAst): Boolean =
      ast match {
        case IdReferenceTyped(_, Location(BuiltIn), _) => true
        case ApExprTyped(IdReferenceTyped(_, Location(BuiltIn), _), _, _, _) => true
        case _ => false
      }
  }


  // NOTE: Type-preserving expansion of partial applications/lambdas into helper methods.
  def lift(l: LetExprTyped, moduleClassName: String): Seq[LetExprTyped] = {
    // TODO - super dirty evil impl, maybe have this be re-usable eventually)
    var additionalLets = Seq.empty[LetExprTyped]
    val newMethodCount = new java.util.concurrent.atomic.AtomicLong(0L)
    def makeMethodName(n: String): String =
      s"${n}$$lambda$$${newMethodCount.getAndIncrement}"
    val newCurriedCount = new java.util.concurrent.atomic.AtomicLong(0L)
    def makeCurriedMethodName(n: String): String =
      s"${n}$$curied$$${newCurriedCount.getAndIncrement}"

    // NOTE - There's an error here where argument types are erased somehow...
    def liftImpl(expr: TypedAst): TypedAst =
       expr match {
         // If we have a partial application of a built-in method, we need to construct
         // a raw method which we can lift.
         case PartialApplication(ap @ IsBuiltIn()) =>
           // NOTE - Remember to recurse into methods
           val (allArgTypes, returnType) = deconstructArgs(ap.name.tpe)
           val liftedArgNames = allArgTypes.zipWithIndex.map(x => s"arg${x._2}" -> x._1)
           val lambdaMethodName = makeMethodName(l.name)
           // The underlying lambda we're lifting.
           val liftedZero = {
             LetExprTyped(lambdaMethodName, liftedArgNames.map(_._1),
               ApExprTyped(ap.name,
                 for((name, tpe) <- liftedArgNames)
                 yield IdReferenceTyped(name, TypeEnvironmentInfo(name, Argument, tpe)),
                 returnType,
                 ap.pos
               )
               , ap.name.tpe)
           }
           additionalLets = liftedZero +: additionalLets
           // Now we lift this non-built-in partial application into helper lambdas recursively.
           liftImpl(ApExprTyped(
             IdReferenceTyped(lambdaMethodName, TypeEnvironmentInfo(lambdaMethodName, StaticMethod(moduleClassName, lambdaMethodName, allArgTypes, returnType), ap.name.tpe)),
             ap.args.map(liftImpl),
             ap.tpe))
         // We only need to lift if the partial application is more than one extra method.
         case PartialApplicationMoreThanOneArg(ap) =>
           // TODO - Peel of one argyent, and lift.
           val newArgLength = ap.args.length + 1
           val (allArgTypes, resultType) = deconstructArgs(ap.name.tpe, newArgLength)
           val newMethodName = makeCurriedMethodName(l.name)
           val newArgList = allArgTypes.zipWithIndex.map(x => s"arg${x._2}" -> x._1)

           val liftedDelegate = {
             LetExprTyped(newMethodName, newArgList.map(_._1),
               ApExprTyped(ap.name,
                 for((name, tpe) <- newArgList)
                 yield IdReferenceTyped(name, TypeEnvironmentInfo(name, Argument, tpe)),
                 resultType,  // TODO - fix this?
                 ap.pos
               ), ap.name.tpe)
           }
           additionalLets = liftImpl(liftedDelegate).asInstanceOf[LetExprTyped] +: additionalLets
           // TODO - figure out the type of the new function...
           ApExprTyped(
             IdReferenceTyped(newMethodName, TypeEnvironmentInfo(newMethodName, StaticMethod(moduleClassName, newMethodName, allArgTypes, resultType), ap.name.tpe)),
             ap.args.map(liftImpl),
             ap.tpe)
         case ApExprTyped(name, args, tpe, pos) => ApExprTyped(name, args.map(liftImpl), tpe, pos)
         case LetExprTyped(name, arg, defn, tpe, pos) => LetExprTyped(name, arg, liftImpl(defn), tpe, pos)
         case _ => expr
       }

    val result = liftImpl(l).asInstanceOf[LetExprTyped]
    result +: additionalLets
  }


  // Here we need to:
  // 1. Figure out all unbound argument tpyes and name them in the apply method.
  // 2. Find a way to encode all bound variables as AST nodes when we feed to existing classfile generation code.
  def deconstructArgs(tpe: Type): (Seq[Type], Type) = {
    def deconstructArgs(argList: Seq[Type], nextFunc: Type): Seq[Type] = nextFunc match {
      case Function(arg, next) => deconstructArgs(argList :+ arg, next)
      case result => argList :+ result
    }
    val all = deconstructArgs(Nil, tpe)
    (all.init, all.last)
  }

  // Similar to above, but with a limited amount of deconstruction.
  def deconstructArgs(tpe: Type, args: Int): (Seq[Type], Type) = {
    def deconstructArgs(argList: Seq[Type], nextFunc: Type, remaining: Int): Seq[Type] = nextFunc match {
      case Function(arg, next) if remaining > 0 => deconstructArgs(argList :+ arg, next, remaining -1)
      case result => argList :+ result
    }
    val all = deconstructArgs(Nil, tpe, args)
    (all.init, all.last)
  }

}
