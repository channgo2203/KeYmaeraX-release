package edu.cmu.cs.ls.keymaerax.bellerophon

import edu.cmu.cs.ls.keymaerax.btactics.{DerivationInfo, Generator}
import edu.cmu.cs.ls.keymaerax.core.{Expression, Formula, Term}

/**
  * Constructs a [[edu.cmu.cs.ls.keymaerax.bellerophon.BelleExpr]] from a tactic name
  * @author Nathan Fulton
  * @author Brandon Bohrer
  */
object ReflectiveExpressionBuilder {
  def build(info: DerivationInfo, args: List[Either[Expression, Position]], generator: Option[Generator[Formula]]): BelleExpr = {
    val posArgs = args.filter{case arg => arg.isRight}.map{case arg => arg.right.get}
    val withGenerator =
      if (info.needsGenerator) {
        info.belleExpr.asInstanceOf[Generator[Formula] => Any](generator.get)
      } else {
        info.belleExpr
      }
    val expressionArgs = args.filter{case arg => arg.isLeft}.map{case arg => arg.left.get}
    val applied:Any = expressionArgs.foldLeft(withGenerator) {
      case ((expr:(Formula => Any)), fml:Formula) => expr(fml)
      case ((expr:(Term => Any)), term:Term) => expr(term)
      case (expr:(Any), fml) =>
        throw new Exception("Expected type Formula => Any , got " + expr.getClass.getSimpleName)
    }

    (applied, posArgs, info.numPositionArgs) match {
      // If the tactic accepts arguments but wasn't given any, return the unapplied tactic under the assumption that
      // someone is going to plug in the arguments later
      case (expr:BelleExpr, Nil, _) => expr
      case (expr:BelleExpr with PositionalTactic , arg::Nil, 1) => AppliedPositionTactic(expr, Fixed(arg))
      case (expr:DependentPositionTactic, arg::Nil, 1) => new AppliedDependentPositionTactic(expr, Fixed(arg))
      case (expr:BuiltInTwoPositionTactic, arg1::arg2::Nil, 2) =>
        AppliedTwoPositionTactic(expr, arg1, arg2)
      case (expr, posArgs, num) =>
        if (posArgs.length > num) {
          throw new Exception("Expected either " + num + " or 0 position arguments, got " + posArgs.length)
        } else {
          throw new Exception("Tactics with " + num + " arguments cannot have type " + expr.getClass.getSimpleName)
        }
    }
  }

  def apply(name: String, arguments: List[Either[Expression, Position]] = Nil, generator: Option[Generator[Formula]]) : BelleExpr =
    try {
      build(DerivationInfo.ofCodeName(name), arguments, generator)
    }
    catch {
      case e:java.util.NoSuchElementException =>
        println("Error" + e)
        throw new Exception(s"Identifier '$name' is not recognized as a tactic identifier.")
    }
}
