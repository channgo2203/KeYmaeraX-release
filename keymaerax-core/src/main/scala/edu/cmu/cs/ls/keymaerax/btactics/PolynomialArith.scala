package edu.cmu.cs.ls.keymaerax.btactics

import edu.cmu.cs.ls.keymaerax.core.{Variable, _}
import edu.cmu.cs.ls.keymaerax.btactics.TactixLibrary._
import edu.cmu.cs.ls.keymaerax.btactics.Idioms._
import edu.cmu.cs.ls.keymaerax.btactics.Augmentors._

import scala.collection.immutable.{Map, _}
import edu.cmu.cs.ls.keymaerax.pt.ProvableSig
import edu.cmu.cs.ls.keymaerax.parser.StringConverter._
import edu.cmu.cs.ls.keymaerax.bellerophon.{OnAll, RenUSubst, _}

/**
  * Created by yongkiat on 11/27/16.
  */
object PolynomialArith {

  private val DEBUG = false

  /**
    * Normalised polynomial arithmetic
    *
    * A normalised monomial has the following shape in KeYmaeraX's term grammar:
    *
    * mono:= 1 | mono * (Var)^n
    *
    * Variables in the first half of * must be lexicographically > than the variable in the second by VarOrd
    * ("Variables" is meant a bit loosely in that nullary function symbols are allowed too)
    * (n must be a Number)
    *
    * Similarly, a normalised polynomial has the following shape (const is non-zero):
    *
    * poly:= 0 | poly + const * mono
    *
    * monomials in the second half of + must be smaller than the first half by monomial ordering
    * const must be a rational coefficient represented by Divide(Number,Number),
    * with both numerator != 0, denominator > 0 and they must be co-prime
    *
    * The units are included for now to get a nicer definition??
    *
    * todo: Add ability to turn off proof generation everywhere
    */

  //Assumes that the terms are Variables or nullary Functions
  // Default: x < y, x < x(), y < x()
  // Invalid inputs lead to transitive failure: a+b = x = c+d = x()
  object VarOrd extends Ordering[Term] {
    def compare(x: Term, y: Term): Int =
      (x,y) match {
        //todo: remove the prettyString and properly check things like wit_1 wit_0
        case (vx:Variable,vy:Variable) => vx.prettyString.compare(vy.prettyString)
        case (FuncOf(fx,Nothing),vy:Variable) => 1
        case (vx:Variable,FuncOf(fy,Nothing)) => -1
        case (FuncOf(fx,Nothing),FuncOf(fy,Nothing)) => fx.name.compare(fy.name)
        case _ => 0
      }
  }

  def isVar(s:Term) : Boolean = {
    s match {
      case _:Variable | FuncOf(_,Nothing) => true
      case _ => false
    }
  }

  //Sanity check that a term representing a monomial satisfies the monomial normalisation requirement
  //Additional check: the terms in "variable" position are actual variables
  //The nested variables i.e. those in l must be lexicographically smaller so (a^5)*b^3 is valid, (b^3)*a^5 is invalid
  def checkMono(t:Term,maxs:Option[Term] = None): Boolean = {
    if(DEBUG) println("Checking ",t,maxs)
    t match {
      case n:Number => n.value == 1
      case Times(l,Power(s,n:Number)) if isVar(s) =>
        n.value.isValidInt && n.value.toInt > 0 &&
        maxs.forall(t=>VarOrd.compare(s,t) < 0 ) && checkMono(l,Some(s))
      case _ => false
    }
  }

  //Monomial order (of a normalized monomial)
  def ordMono(t:Term) : Integer = {
    //assert(checkMono(t))
    t match {
      case Times(l,Power(_,n:Number)) => n.value.toInt + ordMono(l)
      case _ => 0
    }
  }

  object MonOrd extends Ordering[Term] {
    //Reverse lexicographic order for monomials of the same degree
    private def lexMono(l:Term,r:Term) : Int = {
      (l,r) match {
        case (n: Number, m: Number) => 0 //Impossible for normalized monomials
        case (Times(l, Power(vl, nl: Number)), Times(r, Power(vr, nr: Number))) =>
          val cmp = VarOrd.compare(vl,vr)
          if (cmp == 0) {
            if (nl.value < nr.value) -1
            else if (nl.value == nr.value) lexMono(l, r)
            else 1
          }
          else cmp
        case _ => ???
      }
    }

    def compare(l: Term, r: Term): Int = {
      val ol = ordMono(l)
      val or = ordMono(r)
      if(DEBUG) println("monos:",l,r,ol,or)
      if (ol < or) -1
      else if(ol > or) 1
      else lexMono(l,r)
    }
  }

  def gcd(a: BigDecimal,b: BigDecimal):  BigDecimal = {
    if(b ==0) a else gcd(b, a%b)
  }

  def checkCoeff(t:Term) : Boolean = {
    t match {
      case Divide(numer:Number,denom:Number) =>
        numer.value != 0 && denom.value > 0 && gcd(numer.value,denom.value).abs==1
      case _ => false
    }
  }

  //Sanity check that a term representing a polynomial satisfies the normalisation requirement
  def checkPoly(t:Term,maxm:Option[Term] = None): Boolean = {
    if(DEBUG) println("Checking",t,maxm)
    t match {
      case n:Number => n.value == 0
      case Plus(l,Times(c,m)) =>
        checkCoeff(c) && checkMono(m) &&
          (maxm match {
          case None => checkPoly(l,Some(m))
          case Some(n) => {
            MonOrd.compare(m,n) < 0 && checkPoly(l,Some(m))
          }
          })
      case _ => false
    }
  }

  //List of reassociations needed -- avoids QE inside the actual proof (QE should get everything right)
  private lazy val plusAssoc1 = proveBy("(F_() + G_()) + (A_() + B_()) = ((F_()+G_())+A_())+B_()".asFormula,QE & done)
  private lazy val plusAssoc2 = proveBy("(F_() + K_()*M_()) + (A_() + L_()*M_()) = (F_()+A_())+(K_()+L_())*M_()".asFormula,QE & done)

  private lazy val plusCoeff1 = proveBy("K_() = 0 -> (F_() + K_()*M_() = F_())".asFormula,QE & done)
  private lazy val plusCoeff2 = proveBy("K_() = L_() -> (F_() + K_()*M_() = F_() + L_()*M_())".asFormula,
    byUS("const congruence"))

  //This seems like it might be a bad idea ...
  private def getProver(skip_proofs:Boolean) :(Formula,BelleExpr)=>ProvableSig =
    if (skip_proofs) ( (f:Formula,b:BelleExpr) => DerivedAxioms.equivReflexiveAxiom.fact ) else proveBy

  def addCoeff(cl:Term,cr:Term) : (Term,Boolean) = {
    (cl,cr) match {
      case(Divide(lnum:Number,lden:Number),Divide(rnum:Number,rden:Number)) =>
        val num = lnum.value*rden.value + rnum.value * lden.value
        val den = lden.value * rden.value
        val normalizer = gcd(num,den).abs
        (Divide(Number(num/normalizer),Number(den/normalizer)),num==0)
      case _ => ???
    }
  }

  //Takes and returns normalised polynomials
  //The returned provable is just reflexivity if no proof is required
  def addPoly(l:Term,r:Term,skip_proofs:Boolean = false): (Term,ProvableSig) = {
    val lhs = Plus(l,r)
    val prover = getProver(skip_proofs)
    val res = (l,r) match {
      case (n:Number,_) => //Left unit for addition
        (r,prover(Equal(lhs,r), byUS("0+")))
      case (_,n:Number) => //Right unit for addition
        (l,prover(Equal(lhs,l), byUS("+0")))
      case (Plus(nl,Times(cl,ml)),Plus(nr,Times(cr,mr))) => {
        val cmp = MonOrd.compare(ml,mr)
        if(cmp < 0) {
          val (rec,pr) = addPoly(l,nr,skip_proofs)
          val res = Plus(rec,Times(cr,mr))
          (res,prover(Equal(lhs,res), useAt(plusAssoc1)(SuccPosition(1,0::Nil))
            & useAt(pr)(SuccPosition(1,0::0::Nil)) & byUS("= reflexive")))
        }
        else if (cmp == 0) {
          val (rec,pr) = addPoly(nl,nr,skip_proofs)
          val (cnew,isZero) = addCoeff(cl,cr)
          if (isZero) //Canceling out the 0
            (rec, prover(Equal(lhs,rec), useAt(plusAssoc2)(SuccPosition(1,0::Nil))
              & useAt(pr)(SuccPosition(1,0::0::Nil))
              & useAt(plusCoeff1,PosInExpr(1::Nil))(1)
              //Only for coefficient calculation
              & TactixLibrary.RCF))
          else {
            val res = Plus(rec,Times(cnew,ml))
            (res, prover(Equal(lhs,res), useAt(plusAssoc2)(SuccPosition(1,0::Nil))
              & useAt(pr)(SuccPosition(1,0::0::Nil))
              & useAt(plusCoeff2,PosInExpr(1::Nil))(1)
              //Only for coefficient calculation
              & RCF))
          }
        }
        else {
          val (rec,pr) = addPoly(r,l,skip_proofs)
          //Flip the +
          (rec,if (skip_proofs) DerivedAxioms.equivReflexiveAxiom.fact else useFor("+ commute")(SuccPosition(1,0::Nil))(pr))
        }
      }
      case _ => ???
    }
    res
  }

  //One of these is missing in DerivedAxioms
  private lazy val onetimes = proveBy("1*F_() = F_()".asFormula,QE & done)
  private lazy val timesone = proveBy("F_()*1 = F_()".asFormula,QE & done)

  private lazy val timesAssoc1 = proveBy("(F_() * G_()) * (A_() * B_()) = ((F_()*G_())*A_())*B_()".asFormula,QE & done)
  private lazy val timesAssoc2 = proveBy("(F_() * M_()^K_()) * (A_() * M_()^L_()) = (F_()*A_())*M_()^(K_()+L_())".asFormula,QE & done)

  //QE has interesting ideas about X^0
  private lazy val timesCoeff1Lem = proveBy("F_() = F_() * M_() ^ 0".asFormula,QE & done)
  private lazy val timesCoeff1 = proveBy("K_() = 0 -> (F_() * M_()^K_() = F_() )".asFormula,
    useAt(timesCoeff1Lem)(SuccPosition(1,1::1::Nil)) & byUS("const congruence"))

  private lazy val timesCoeff2 = proveBy("K_() = L_() -> (F_() * M_()^K_() = F_() * M_()^L_())".asFormula,
    byUS("const congruence"))

  //Multiplies and returns normalised monomials (this is basically the same as the implementation for adding polynomials)
  def mulMono(l:Term,r:Term,skip_proofs:Boolean = false): (Term,ProvableSig) = {
    val lhs = Times(l,r)
    val prover = getProver(skip_proofs)
    (l,r) match {
      case(n:Number,_) => (r,prover(Equal(lhs,r),byUS(onetimes)))  //Unit
      case (_,n:Number) => (l,prover(Equal(lhs,l),byUS(timesone))) //Unit
      case (Times(nl,Power(sl,ml:Number)),Times(nr,Power(sr,mr:Number))) =>
        val cmp = VarOrd.compare(sl,sr)
        if(cmp < 0)
        {
          val(rec,pr) = mulMono(l,nr,skip_proofs)
          val res = Times(rec,Power(sr,mr:Number))
          (res,prover(Equal(lhs,res), useAt(timesAssoc1)(SuccPosition(1,0::Nil))
            & useAt(pr)(SuccPosition(1,0::0::Nil)) & byUS("= reflexive")))
        }
        else if(cmp == 0) {
          val(rec,pr) = mulMono(nl,nr,skip_proofs)
          val mnew = ml.value + mr.value
          if(mnew == 0) //Canceling out the 0
            (rec, prover(Equal(lhs,rec), useAt(timesAssoc2)(SuccPosition(1,0::Nil))
              & useAt(pr)(SuccPosition(1,0::0::Nil))
              & useAt(timesCoeff1,PosInExpr(1::Nil))(1)
              //Only for coefficient calculation
              & RCF))
          else {
            val res = Times(rec,Power(sl,Number(ml.value+mr.value)))
            (res, prover(Equal(lhs,res), useAt(timesAssoc2)(SuccPosition(1,0::Nil))
              & useAt(pr)(SuccPosition(1,0::0::Nil))
              & useAt(timesCoeff2,PosInExpr(1::Nil))(1)
              //Only for coefficient calculation
              & RCF))
          }
        }
        else {
          val (rec,pr) = mulMono(r,l,skip_proofs)
          //Flip the *
          (rec,if (skip_proofs) DerivedAxioms.equivReflexiveAxiom.fact else useFor("* commute")(SuccPosition(1,0::Nil))(pr))
        }
      case _ => ???
    }
  }

  private lazy val timesAssoc3 = proveBy(("(P_() + C_() * M_()) * (D_() * N_()) = " +
    "P_() * (D_() * N_()) + (C_() * D_()) * (M_() * N_())").asFormula,QE & done)

  def mulCoeff(cl:Term,cr:Term) : Term = {
    (cl,cr) match {
      case(Divide(lnum:Number,lden:Number),Divide(rnum:Number,rden:Number)) =>
        val num = lnum.value * rnum.value
        val den = lden.value * rden.value
        val normalizer = gcd(num,den).abs
        Divide(Number(num/normalizer),Number(den/normalizer))
      case _ =>
        ???
    }
  }

  //Multiplies a normalized polynomial (l) by a constant (c) and a normalized monomial (r)
  def mulPolyMono(l:Term,c:Term,r:Term,skip_proofs:Boolean = false): (Term,ProvableSig) = {
    if(DEBUG) println("mul poly, const, mono",l,c,r)
    val lhs = Times(l,Times(c,r))
    val prover = getProver(skip_proofs)
    l match {
      case n:Number => (n,prover(Equal(lhs,n),byUS("0*"))) // Multiplication by 0 poly
      case Plus(nl,Times(cl,ml)) =>
        val (rec1,pr1) = mulPolyMono(nl,c,r,skip_proofs)
        val (rec2,pr2) = mulMono(ml,r,skip_proofs)
        val res =  Plus(rec1,Times(mulCoeff(cl,c),rec2) )

        (res,prover(Equal(lhs,res),useAt(timesAssoc3)(SuccPosition(1,0::Nil))
          & useAt(pr1)(SuccPosition(1,0::0::Nil))
          & useAt(pr2)(SuccPosition(1,0::1::1::Nil))
          & useAt(plusCoeff2,PosInExpr(1::Nil))(1)
          //Should only be simple arithmetic at this step
          & RCF))

      case _ => ???
    }
  }

  //Multiplies and returns normalised polynomials
  def mulPoly(l:Term,r:Term,skip_proofs:Boolean = false): (Term,ProvableSig) = {
    val lhs = Times(l,r)
    val prover = getProver(skip_proofs)
    r match {
      case n:Number => (n,prover(Equal(lhs,n),byUS("*0"))) //Multiplication by 0 poly
      case Plus(nr,Times(cr,mr)) =>
        val (rec1,pr1) = mulPoly(l,nr,skip_proofs)
        val (rec2,pr2) = mulPolyMono(l,cr,mr,skip_proofs)
        val (res,pr3) = addPoly(rec1,rec2,skip_proofs)
        (res,prover(Equal(lhs,res),useAt("distributive")(SuccPosition(1,0::Nil))
          & useAt(pr1)(SuccPosition(1,0::0::Nil))
          & useAt(pr2)(SuccPosition(1,0::1::Nil))
          & by(pr3)
        ))

      case _ => ???
    }
  }

  private lazy val powLem1 = proveBy("F_()^0 = 1".asFormula,QE & done)
  private lazy val powLem2 = proveBy("F_()^1 = F_()".asFormula,QE & done)
  private lazy val powLem3 = proveBy("(F_()^K_())^2 = F_()^(2*K_())".asFormula,QE & done)
  private lazy val powLem4 = proveBy("(F_()^K_())^2 * F_() = F_()^(2*K_()+1)".asFormula,QE & done)
  private lazy val powLem5 = proveBy("K_() = L_() -> (M_()^K_() = M_()^L_())".asFormula,
    byUS("const congruence"))

  //Reduces t^n to iterated squares
  def iterSquare(l:Term,p:Int,skip_proofs:Boolean = false) : (Term,ProvableSig) = {
    val lhs = Power(l,Number(p))
    val prover = getProver(skip_proofs)
    if (p == 0) (Number(1),prover(Equal(lhs,Number(1)),byUS(powLem1)))
    else if (p == 1) (l,prover(Equal(lhs,l),byUS(powLem2)))
    else if (p % 2 == 0)
    {
      val (rec,pr) = iterSquare(l,p/2,skip_proofs)
      val rhs = Power(rec,Number(2))
      (rhs,prover(Equal(lhs,rhs),
        useAt(pr,PosInExpr(1::Nil))(SuccPosition(1,1::0::Nil)) &
        useAt(powLem3)(SuccPosition(1,1::Nil)) &
        useAt(powLem5,PosInExpr(1::Nil))(1) & RCF))
    }
    else
    {
      val (rec,pr) = iterSquare(l,p/2,skip_proofs)
      val rhs = Times(Power(rec,Number(2)),l)
      (rhs,prover(Equal(lhs,rhs),
        useAt(pr,PosInExpr(1::Nil))(SuccPosition(1,1::0::0::Nil)) &
        useAt(powLem4)(SuccPosition(1,1::Nil)) &
        useAt(powLem5,PosInExpr(1::Nil))(1) & RCF))
    }
  }

  private lazy val negNormalise = proveBy("-P_() = P_() * (-1/1 * 1)".asFormula,QE & done)
  private lazy val minusNormalise = proveBy("P_()-Q_() = P_() + -(Q_())".asFormula,QE & done)
  private lazy val powNormalise = proveBy("P_()^2 = P_() * P_()".asFormula,QE & done)

  def divCoeff(cl:Term,cr:Term) : Term = {
    (cl,cr) match {
      case(Divide(lnum:Number,lden:Number),Divide(rnum:Number,rden:Number)) =>
        val num = lnum.value * rden.value
        val den = lden.value * rnum.value
        val normalizer = gcd(num,den).abs
        Divide(Number(num/normalizer),Number(den/normalizer))
      case _ =>
        ???
    }
  }

  def ratToNum(t:Term) : Option[Number] = {
    t match{
      case Divide(n:Number,d:Number) if d.value == 1 => Some(n)
      case _ => None
    }
  }

  // Try to turn a ground term into an equivalent normalized rational (A/B)
  // Proves the equivalence using RCF
  // The proof gets generated afterwards using RCF
  def groundNormalise(t:Term) : Option[Term] = {
    t match{
      case (n:Number) => Some(Divide(n,Number(1)))
      case Power(l,r) => {
        val ln = groundNormalise(l)
        val rn = groundNormalise(r).flatMap(ratToNum)
        (ln, rn) match {
          case (Some(Divide(n: Number, d: Number)), Some(p: Number)) =>
            Some(Divide(Number(n.value.pow(p.value.intValue())), Number(d.value.pow(p.value.intValue()))))
          case _ => None
        }
      }
      case bop:BinaryCompositeTerm =>
        (groundNormalise(bop.left),groundNormalise(bop.right)) match {
          case (Some(l),Some(r)) =>
            bop match{
              case Plus(_,_) => Some(addCoeff(l,r)._1)
              case Minus(_,_) => Some(addCoeff(l,mulCoeff(Divide(Number(-1),Number(1)),r))._1)
              case Times(_,_) => Some(mulCoeff(l,r))
              case Divide(_,_) => Some(divCoeff(l,r))
              case _ => None
            }
          case _=> None
        }
      case Neg(u) =>
        groundNormalise(u).map( t => mulCoeff(Divide(Number(-1),Number(1)),t))
      case _ => None
    }
  }

  def groundNormaliseProof(t:Term,toNum:Boolean=false) : Option[(Term,ProvableSig)] = {
    val gt = groundNormalise(t)
    (if(toNum) gt.flatMap(ratToNum) else gt) match{
      case Some(tt) =>
        val pr = proveBy(Equal(t,tt),?(RCF))
        if (pr.isProved) Some(tt,pr) else None
      case _ => None
    }
  }


  private lazy val divNormalise = proveBy(" P_() / Q_()  = (1/Q_()) *P_() ".asFormula,QE & done)
  private lazy val varNormalise = proveBy("P_() = 0 + (1/1) * (1 * P_() ^ 1)".asFormula,QE & done)

  //Normalizes an otherwise un-normalized term
  def normalise(l:Term,skip_proofs:Boolean = false) : (Term,ProvableSig) = {
    if(DEBUG) println("Normalizing at",l)
    val prover = getProver(skip_proofs)
    val res = l match {
      case n:Number =>
        //0 + 1 * n (unless n = 0)
        val res = if (n.value == 0) n else Plus(Number(0), Times(Divide(n,Number(1)),Number(1)))
        (res,prover(Equal(l,res), RCF ))
      case v if isVar(v) =>
        //0 + 1/1 * (1 * v^1)
        val res = Plus(Number(0),Times(Divide(Number(1),Number(1)), Times(Number(1),Power(v,Number(1))) ))
        (res,prover(Equal(l,res), byUS(varNormalise) ))
      case Plus(ln,rn) =>
        val (rec1,pr1) = normalise(ln,skip_proofs)
        val (rec2,pr2) = normalise(rn,skip_proofs)
        val (res,pr3) = addPoly(rec1,rec2,skip_proofs)
        (res,prover(Equal(l,res), useAt(pr1)(SuccPosition(1,0::0::Nil))
          & useAt(pr2)(SuccPosition(1,0::1::Nil))
          & by(pr3)))
      case Times(ln,rn) =>
        val (rec1,pr1) = normalise(ln,skip_proofs)
        val (rec2,pr2) = normalise(rn,skip_proofs)
        val (res,pr3) = mulPoly(rec1,rec2,skip_proofs)
        (res,prover(Equal(l,res), useAt(pr1)(SuccPosition(1,0::0::Nil))
          & useAt(pr2)(SuccPosition(1,0::1::Nil))
          & by(pr3)))
      case Power(_:Variable,_:Number) =>
        //0 + 1 * (1 * v^n)
        val res = Plus(Number(0),Times(Divide(Number(1),Number(1)), Times(Number(1),l) ))
        (res,prover(Equal(l,res),RCF))
      case Power(ln,n:Number) if n.value == 2 =>
        val (rec1,pr1) = normalise(ln,skip_proofs)
        //Square the polynomial by hand
        val (res,pr2) = mulPoly(rec1,rec1,skip_proofs)
        (res,prover(Equal(l,res), useAt(pr1)(SuccPosition(1,0::0::Nil))
          & useAt(powNormalise)(SuccPosition(1,0::Nil))
          & by(pr2)))
      case Power(ln,n:Number) if n.value.isValidInt =>
        val(rec1,pr1) = iterSquare(ln,n.value.intValue(),skip_proofs)
        val(res,pr2) = normalise(rec1,skip_proofs)
        (res,prover(Equal(l,res), useAt(pr1)(SuccPosition(1,0::Nil))
          & by(pr2)))
      //If the power is not itself a number, try harder to make it a Number
      case Power(ln,e:Term) =>
        val pr = groundNormaliseProof(e,true)
        pr match {
          case None => ???  // Could not normalize the power
          case Some((n:Number,pr)) => {
            val (res,pr2) = normalise(Power(ln,n),skip_proofs)
            (res,prover(Equal(l,res), useAt(pr)(SuccPosition(1,0::1::Nil)) & by(pr2)))
          }
        }
      case Neg(ln) =>
        //Negation ~= multiply by -1 monomial
        val (rec1,pr1) = normalise(ln,skip_proofs)
        val (res,pr2) = mulPolyMono(rec1,Divide(Number(-1),Number(1)),Number(1),skip_proofs)
        (res,prover(Equal(l,res), useAt(pr1)(SuccPosition(1,0::0::Nil))
          & useAt(negNormalise)(SuccPosition(1,0::Nil)) & by(pr2) ))
      case Minus(ln,rn) =>
        //Minus ~= move - into negation
        val (rec1,pr1) = normalise(ln,skip_proofs)
        val (rec2,pr2) = normalise(Neg(rn),skip_proofs)
        val (res,pr3) = addPoly(rec1,rec2,skip_proofs)
        (res,prover(Equal(l,res), useAt(minusNormalise)(SuccPosition(1,0::Nil))
          & useAt(pr1)(SuccPosition(1,0::0::Nil))
          & useAt(pr2)(SuccPosition(1,0::1::Nil))
          & by(pr3) ))
      case Divide(num:Number,den:Number) =>
        val res = if (num.value == 0) num else Plus(Number(0), Times(l,Number(1)))
        (res,prover(Equal(l,res), RCF ))
      case Divide(ln,e:Term) =>
        //Simple hack: Try hard to convert a division to a number
        val propt = groundNormaliseProof(Divide(Number(1),e))
        propt match {
          case None =>
            ???  // Could not normalize
          case Some((n,pr)) => {
            val (res,pr2) = normalise(Times(n,ln),skip_proofs)
            (res,prover(Equal(l,res), useAt(divNormalise)(SuccPosition(1,0::Nil)) &
              useAt(pr)(SuccPosition(1,0::0::Nil)) &
              by(pr2)
            ))
          }
        }
      case _ => {
        ???
      }
    }
    res
  }

  lazy val normaliseAt:DependentPositionTactic = new DependentPositionTactic("normalise at"){
    override def factory(pos: Position): DependentTactic = new SingleGoalDependentTactic(name) {
      override def computeExpr(sequent: Sequent): BelleExpr = {
        sequent.sub(pos) match
        {
          case Some(t:Term) =>
            val(tt,pr) = normalise(t,false)
            //println(tt,pr)
            CEat(useFor("= commute")(SuccPos(0))(pr))(pos)
          case _ => ident
        }
      }
    }
  }

  //Polynomial division: no proof needed, although the polynomials need to be pre-normalised
  //todo: Might this be implemented in terms of mulMono with -ve power? (probably not because ordering gets messed up)

  //Monomial division l/r : returns the normalised quotient monomial q s.t. q * r = l only if it is truly divisible
  def divMono(l:Term,r:Term): Option[Term] = {
    val lhs = Times(l,r)
    (l,r) match {
      case (_,n:Number) => Some(l) //Divide by 1
      case(n:Number,_) => None //Dividing 1 by something not 1
      case (Times(nl,Power(sl,ml:Number)),Times(nr,Power(sr,mr:Number))) =>
        val cmp = VarOrd.compare(sl,sr)
        if(cmp < 0) None
        else if(cmp == 0) {
          val mnew = ml.value - mr.value
          if (mnew < 0) None
          else
          divMono(nl, nr) match {
            case None => None
            case Some(q) =>
              Some (if (mnew == 0) q else Times(q, Power(sr, Number(mnew))))
          }
        }
        else {
          divMono(nl,r) match
          {
            case None => None
            case Some(q) => Some(Times(q,Power(sl,ml)))
          }
        }
      case _ => ???
    }
  }

  //Polynomial division on head monomials (no proofs)

  //Find the first non-zero monomial in l that r divides if it exists & returns the quotient along with its coefficient
  def divPolyMono(l:Term,r:Term) : Option[(Term,Term)] = {
    l match {
      case n: Number => None //We want non-zero monomials only
      case Plus(nl, Times(cl, ml)) =>
        divMono(ml, r) match {
          case None => divPolyMono(nl, r)
          case Some(q) => Some(cl, q)
        }
    }
  }

  def negDivCoeff(cl:Term,cr:Term) : Term = {
    (cl,cr) match {
      case(Divide(lnum:Number,lden:Number),Divide(rnum:Number,rden:Number)) =>
        val num = lnum.value * rden.value
        val den = lden.value * rnum.value
        val normalizer = gcd(num,den).abs
        //Manually flips the sign
        if(den < 0) Divide(Number(num/normalizer),Number(-den/normalizer))
        else
          Divide(Number(-num/normalizer),Number(den/normalizer))
      case _ =>
        ???
    }
  }

  //Returns the divisor and quotient (if one exists)
  def divPoly(l:Term,r:Term): Option[(Term,Term)] = {
    r match {
      case n:Number => None //Division by 0
      case Plus(nr,Times(cr,mr)) =>
        divPolyMono(l,mr) match {
          case None => None //No monomial in l divisible by r
          case Some((c,q)) => //The monomial c*(q*mr) was in l
            //The actual coefficient we need to return for the reduction:
            val divisor = Times(negDivCoeff(c,cr),q)
            //For division, we need to use the normalized version internally
            val quotient = addPoly(l, mulPolyMono(r, negDivCoeff(c,cr), q,true)._1,true)._1
            Some(divisor,quotient)
        }
    }
  }

  //Don't know how to do this in Scala neatly
  def firstDivisor(l:List[Term],i:Int,r:Term) : Option[(Int,Term,Term)] ={
    l match {
      case Nil => None
      case (x::xs) =>
        divPoly(r,x) match {
          case None => firstDivisor(xs,i+1,r)
          case Some((d,q)) => Some(i,d,q)
        }
    }
  }

  //Repeated division of normalized things
  //Returns the sequence of reduction steps, each a pair of the index and divisor of the dividing polynomial
  def reduction(l:List[Term],r:Term): List[(Int,Term)] ={
    firstDivisor(l,0,r) match {
      case None => Nil
      case Some((i,d,q)) =>
        (i,d)::reduction(l,q)
    }
  }

  //This lemma should be in DerivedAxioms together with 1>0 and f_()^2 >= 0
  private lazy val plusGtMono: ProvableSig = proveBy("(f_() > k_() & g_() >= 0) -> f_() + g_() > k_()".asFormula,QE)
  private lazy val timesPos: ProvableSig = proveBy("(f_() >= 0 & g_() >= 0) -> f_() * g_() >= 0".asFormula,QE)

  //Doesn't use QE, but the DerivedAxioms used do
  lazy val notZeroGt: ProvableSig = proveBy("!(0>0)".asFormula,
    notR(1) & useAt(">2!=")(-1) & useAt("! =",PosInExpr(1::Nil))(-1) & notL(-1) & byUS("= reflexive"))

  // Generate a proof for |- !(1 + a_1 * s_1^2 + ... + a_n * s_n^2 = 0)
  // Each a_i should be a positive (rational) coefficient (proved by RCF)
  def assertWitness(l:List[(Term,Term)]) : (Term,ProvableSig) =
  {
    l match {
      case Nil => (Number(1),DerivedAxioms.oneGreaterZero.fact)
      case ((a,s)::xs) =>
        val (rec,pr) = assertWitness(xs) //1 + foo > 0
        val res = Plus(rec,Times(a,Power(s,Number(2))))
        (res,proveBy(Greater(res,Number(0)), // (1+ foo) + x^2 >0
          useAt(plusGtMono,PosInExpr(1::Nil))(1) & andR(1) <( by(pr),
            useAt(timesPos,PosInExpr(1::Nil))(1) & andR(1) <( RCF, byUS("nonnegative squares") ) )))
    }
  }

  //todo: more convenient to cut in, can be derived without QE from something else
  private lazy val gtNotZero: ProvableSig = proveBy("f_() > 0 -> !(f_() = 0)".asFormula,QE & done)

  // Given a list representing a (hopefully Groebner) basis g_1, ... g_k, a witness, and
  // an optional list of instructions (detailing the coefficients) and a list of witnesses s_i ^2
  // Proves the contradiction g_1 = 0 ; ... g_k = 0 |-
  // Nothing needs to be normalized?

  private lazy val axMov: ProvableSig = proveBy("f_() + a_() * g_() = k_() -> (a_() = 0 -> f_() = k_())".asFormula,QE & done)

  def proveWithWitness(ctx:List[Term], witness:List[(Term,Term)], instopt:Option[List[(Int,Term)]] = None) : ProvableSig = {
    val antes = ctx.map( t => Equal(t,Number(0)))
    val (wit,pfi) = assertWitness(witness)
    val pf = useFor(gtNotZero,PosInExpr(0::Nil))(SuccPosition(1))(pfi)
    //assert(pf.isProved)
    //Generate our own reduction instructions if not available
    //Proofs skipped
    val inst = instopt.getOrElse({
      val wit_norm = normalise(wit,true)._1
      val ctx_norm = ctx.map( t=> normalise(t,true)._1)
      reduction(ctx_norm,wit_norm)
    })
    proveBy(Sequent(antes.toIndexedSeq,IndexedSeq()),
      //1+s_i^2 > 0
      cut(pf.conclusion.succ.head) <(
        notL('Llast) &
        //Run the instructions
        inst.foldRight(ident)(
          (h,tac) =>
          implyRi(keep=true)(AntePos(h._1),SuccPos(0))
          & useAt("ANON", axMov,PosInExpr(1::Nil),(us:Option[Subst])=>us.get++RenUSubst(("g_()".asTerm,h._2)::Nil))(1)
          & tac) &
        normaliseAt(SuccPosition(1,0::Nil)) &
        ?(cohideR(1) & byUS("= reflexive"))
        ,
        cohideR(1) & by(pf)
        ))
  }

  //Goal must be of the form (G1=0, G2=0, ... Gn =0 |- )
  def witnessTac(witness:List[(Term,Term)], instopt:Option[List[(Int,Term)]] = None) : DependentTactic = new SingleGoalDependentTactic("ANON") {

    override def computeExpr(sequent: Sequent): BelleExpr = {
      //All =0 polynomials
      val ante_polys = sequent.ante.map(_ match {
        case Equal(t, n: Number) if n.value == 0 => t
        case _ => ??? //Should we generalize here?
      }
      ).toList
      //Assert the witness provided
      val (wit, pfi) = assertWitness(witness)
      val pf = useFor(gtNotZero, PosInExpr(0 :: Nil))(SuccPosition(1))(pfi)
      //assert(pf.isProved)
      //Generate our own reduction instructions if not available
      //Proofs skipped
      val inst = instopt.getOrElse({
        val wit_norm = normalise(wit, true)._1
        val ctx_norm = ante_polys.map(t => normalise(t, true)._1)
        reduction(ctx_norm, wit_norm)
      })
      //1+s_i^2 > 0
      cut(pf.conclusion.succ.head) < (
        notL('Llast) &
          //Run the instructions
          inst.foldRight(ident)(
            (h, tac) =>
              implyRi(keep = true)(AntePos(h._1), SuccPos(0))
                & useAt("ANON", axMov, PosInExpr(1 :: Nil), (us: Option[Subst]) => us.get ++ RenUSubst(("g_()".asTerm, h._2) :: Nil))(1)
                & tac) &
          normaliseAt(SuccPosition(1, 0 :: Nil)) &
          ?(cohideR(1) & byUS("= reflexive"))
        ,
        cohideR(1) & by(pf)
        )
    }
  }

  //Axiomatization

  // Succedent to antecedent for inequations (rewrite left to right followed by notR)
  private lazy val ltSucc: ProvableSig = proveBy(" f_() < g_() <-> !(f_()>=g_())".asFormula,QE)
  private lazy val leSucc: ProvableSig = proveBy(" f_() <= g_() <-> !(f_()>g_())".asFormula,QE)
  private lazy val gtSucc: ProvableSig = proveBy(" f_() > g_() <-> !g_()>=f_()".asFormula,QE)
  private lazy val geSucc: ProvableSig = proveBy(" f_() >= g_() <-> !g_()>f_()".asFormula,QE)
  private lazy val eqSucc: ProvableSig = proveBy(" f_() = g_() <-> !g_()!=f_()".asFormula,QE) //Convenient rule for A3
  private lazy val neSucc: ProvableSig = proveBy(" f_() != g_() <-> !g_()=f_()".asFormula,QE) //Convenient rule for A3

  //(based on note in DerivedAxioms) These require Mathematica QE to prove, will be asserted as axioms
  //note: these folds = 0 normalisation in as well
  //todo: do the existsL naming properly

  private lazy val ltAnte: ProvableSig = proveBy("f_() < g_() <-> \\exists wit_ (f_()-g_())*wit_^2 + 1 = 0".asFormula,QE)
  private lazy val leAnte: ProvableSig = proveBy("f_() <= g_() <-> \\exists wit_ (f_()-g_()) + wit_^2 = 0".asFormula,QE)
  private lazy val gtAnte: ProvableSig = proveBy("f_() > g_() <-> \\exists wit_ (f_()-g_())*wit_^2 - 1 = 0".asFormula,QE)
  private lazy val geAnte: ProvableSig = proveBy("f_() >= g_() <-> \\exists wit_ (f_()-g_()) - wit_^2 = 0".asFormula,QE)

  private lazy val eqAnte: ProvableSig = proveBy("f_() = g_() <-> f_() - g_() = 0".asFormula,QE & done)
  private lazy val neAnte: ProvableSig = proveBy("f_() != g_() <-> \\exists wit_ (f_()-g_())*wit_ - 1 = 0".asFormula,QE & done)

  //todo: generalise to more complete formula fragment
  //todo: Also "clear" equations in succedent after witness generation
  lazy val clearSucc:DependentTactic = new SingleGoalDependentTactic("flip succ") {
    override def computeExpr(seq: Sequent): BelleExpr =
    {
      seq.succ.zipWithIndex.foldLeft(ident) {(tac: BelleExpr, fi) =>
        val ind = fi._2 + 1;
        (fi._1 match {
          case Greater(f, g) => useAt(gtSucc)(ind) & notR(ind)
          case GreaterEqual(f, g) =>  useAt(geSucc)(ind) & notR(ind)
          case Equal(_, _) => useAt(eqSucc)(ind) & notR(ind)
          case NotEqual(_,_) => useAt(neSucc)(ind) & notR(ind)
          case Less(f, g) => useAt(ltSucc)(ind) & notR(ind)
          case LessEqual(f, g) => useAt(leSucc)(ind) & notR(ind)
          case _ => hideR(ind)
        }) & tac
      }
    }
  }

  //Normalizes the antecedent by A4,A5 and skolemizing exists
  lazy val normAnte:DependentTactic = new SingleGoalDependentTactic("norm ante") {
    override def computeExpr(seq: Sequent): BelleExpr = {
      seq.ante.zipWithIndex.foldLeft(ident) { (tac: BelleExpr, fi) =>
        val ind = -(fi._2 + 1);
        (fi._1 match {
          case Greater(f, g) => useAt(gtAnte)(ind) & existsL(ind)
          case GreaterEqual(f, g) => useAt(geAnte)(ind) & existsL(ind)
          case Equal(_, _) => useAt(eqAnte)(ind)
          case NotEqual(_, _) => useAt(neAnte)(ind) & existsL(ind)
          case Less(f, g) => useAt(ltAnte)(ind) & existsL(ind)
          case LessEqual(f, g) => useAt(leAnte)(ind) & existsL(ind)
          case _ => hideL(ind)
        }) & tac
      }
    }
  }

  lazy val prepareArith: BelleExpr = clearSucc & normAnte

  // Guided linear variable elimination at a top-level position (of shape A=B)
  // Rewrites that position to lhs = rhs using polynomial arithmetic to prove its correctness
  private lazy val mulZero: ProvableSig = proveBy("g_() != 0 -> (f_() = 0 <-> g_() * f_() = 0)".asFormula,QE & done)
  //The list of instructions contains:
  // 1) position to rewrite, 2) term to leave on LHS, 3) term on RHS
  // 4) determines the cofactor on the variable (expected to be provable to be non-zero by RCF)
  // The proof works by the following sequence of steps :
  // (clhs = crhs <-> lhs = rhs)
  // <= (clhs - crhs = 0) <-> (lhs - rhs =0)
  // <= (clhs-chrs=0) <-> (K*(lhs-rhs)=0)
  // <= clhs-chrs = K*(lhs-rhs) (by polynomial arithmetic)

  def rewriteEquality(pos:Position, lhs:Term, rhs:Term, cofactor:Term): DependentTactic = new SingleGoalDependentTactic("rewrite equality") {
    override def computeExpr(seq: Sequent): BelleExpr = {
      seq.sub(pos) match {
        case Some(Equal(clhs,crhs)) =>
          val cofact = proveBy(NotEqual(cofactor,Number(0)),RCF)
          val instMulZero = useFor(mulZero,PosInExpr(0::Nil))(Position(1))(cofact)
          val pr =
            proveBy(Equiv(Equal(clhs,crhs),Equal(lhs,rhs)),
              useAt(eqAnte)(1,PosInExpr(0::Nil)) &
                useAt(eqAnte)(1,PosInExpr(1::Nil)) &
                useAt(instMulZero)(1,PosInExpr(1::Nil)) &
                normaliseAt(1,PosInExpr(0::0::Nil)) &
                normaliseAt(1,PosInExpr(1::0::Nil)) &  byUS("<-> reflexive")
            )
          useAt(pr)(pos)
        case _ => ident
      }
    }
  }

  //The actual linear elimination tactic takes a list of AN
//  def linearElim(ls:List[(Int,Term,Term,Term)]) : BelleExpr =
//  ls.foldLeft(ident)( (tac,p) => tac & (rewriteEquality _).tupled(p) & exhaustiveEqL2R)

  val printGoal: DependentTactic = new DependentTactic("linear elim") {
    override def computeExpr(pr:ProvableSig): BelleExpr = {
      pr.subgoals.zipWithIndex.foreach(
        seqind =>
        {
          //println("Goal",seqind._2)
          print("\"")
          seqind._1.ante.foreach(
            _ match {
              case Equal(l,_) => print(l+",")
              case _ => ???
            }
          )
          print("\"\n")
        }
      )
      ident
    }
  }

}
