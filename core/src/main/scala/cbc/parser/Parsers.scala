/* NSC -- new Scala compiler
 * Copyright 2005-2013 LAMP/EPFL
 * @author  Martin Odersky
 */

//todo: allow infix type patterns
//todo verify when stableId's should be just plain qualified type ids

package cbc.parser

import scala.collection.{ mutable, immutable }
import mutable.{ ListBuffer, StringBuilder }
import cbc.{ settings, Precedence, ModifierFlags => Flags }
import cbc.util.{ SourceFile, FreshNameCreator }
import cbc.util.Chars.{ isScalaLetter }
import cbc.parser.Tokens._
import cbc._
import Trees._
import Constants._
import Names._
import FreshNames.{freshTermName, freshTypeName}

/** Historical note: JavaParsers started life as a direct copy of Parsers
 *  but at a time when that Parsers had been replaced by a different one.
 *  Later it was dropped and the original Parsers reinstated, leaving us with
 *  massive duplication between Parsers and JavaParsers.
 *
 *  This trait and the similar one for Scanners/JavaScanners represents
 *  the beginnings of a campaign against this latest incursion by Cutty
 *  McPastington and his army of very similar soldiers.
 */
trait ParsersCommon extends ScannersCommon { self =>

  def newLiteral(const: Any) = Literal(Constant(const))
  def literalUnit            = TreeGen.mkSyntheticUnit()

  /** This is now an abstract class, only to work around the optimizer:
   *  methods in traits are never inlined.
   */
  abstract class ParserCommon {
    val in: ScannerCommon
    def deprecationWarning(off: Offset, msg: String): Unit
    def accept(token: Token): Int

    /** Methods inParensOrError and similar take a second argument which, should
     *  the next token not be the expected opener (e.g. LPAREN) will be returned
     *  instead of the contents of the groupers.  However in all cases accept(LPAREN)
     *  will be called, so a parse error will still result.  If the grouping is
     *  optional, in.token should be tested before calling these methods.
     */
    @inline final def inParens[T](body: => T): T = {
      accept(LPAREN)
      val ret = body
      accept(RPAREN)
      ret
    }
    @inline final def inParensOrError[T](body: => T, alt: T): T =
      if (in.token == LPAREN) inParens(body)
      else { accept(LPAREN) ; alt }

    @inline final def inParensOrUnit[T](body: => Tree): Tree = inParensOrError(body, literalUnit)
    @inline final def inParensOrNil[T](body: => List[T]): List[T] = inParensOrError(body, Nil)

    @inline final def inBraces[T](body: => T): T = {
      accept(LBRACE)
      val ret = body
      accept(RBRACE)
      ret
    }
    @inline final def inBracesOrError[T](body: => T, alt: T): T =
      if (in.token == LBRACE) inBraces(body)
      else { accept(LBRACE) ; alt }

    @inline final def inBracesOrNil[T](body: => List[T]): List[T] = inBracesOrError(body, Nil)
    @inline final def inBracesOrUnit[T](body: => Tree): Tree = inBracesOrError(body, literalUnit)
    @inline final def dropAnyBraces[T](body: => T): T =
      if (in.token == LBRACE) inBraces(body)
      else body

    @inline final def inBrackets[T](body: => T): T = {
      accept(LBRACKET)
      val ret = body
      accept(RBRACKET)
      ret
    }

    /** Creates an actual Parens node (only used during parsing.)
     */
    @inline final def makeParens(body: => List[Tree]): Parens =
      Parens(inParens(if (in.token == RPAREN) Nil else body))
  }
}

/** Performs the following context-free rewritings:
 *
 *  <ol>
 *    <li>
 *      Places all pattern variables in Bind nodes. In a pattern, for
 *      identifiers `x`:<pre>
 *                 x  => x @ _
 *               x:T  => x @ (_ : T)</pre>
 *    </li>
 *    <li>Removes pattern definitions (PatDef's) as follows:
 *      If pattern is a simple (typed) identifier:<pre>
 *        <b>val</b> x = e     ==>  <b>val</b> x = e
 *        <b>val</b> x: T = e  ==>  <b>val</b> x: T = e</pre>
 *
 *      if there are no variables in pattern<pre>
 *        <b>val</b> p = e  ==>  e match (case p => ())</pre>
 *
 *      if there is exactly one variable in pattern<pre>
 *        <b>val</b> x_1 = e <b>match</b> (case p => (x_1))</pre>
 *
 *      if there is more than one variable in pattern<pre>
 *        <b>val</b> p = e  ==>  <b>private synthetic val</b> t$ = e <b>match</b> (case p => (x_1, ..., x_N))
 *                        <b>val</b> x_1 = t$._1
 *                        ...
 *                        <b>val</b> x_N = t$._N</pre>
 *    </li>
 *    <li>
 *       Removes function types as follows:<pre>
 *        (argtpes) => restpe   ==>   scala.Function_n[argtpes, restpe]</pre>
 *    </li>
 *    <li>
 *      Wraps naked case definitions in a match as follows:<pre>
 *        { cases }   ==>   (x => x.match {cases})<span style="font-family:normal;">, except when already argument to match</span></pre>
 *    </li>
 *  </ol>
 */
trait Parsers extends Scanners with MarkupParsers with ParsersCommon { self =>

  case class OpInfo(lhs: Tree, operator: TermName, targs: List[Tree], offset: Offset) {
    def precedence = Precedence(operator.toString)
  }

  class SourceFileParser(val source: SourceFile) extends Parser {

    /** The parse starting point depends on whether the source file is self-contained:
     *  if not, the AST will be supplemented.
     */
    def parseStartRule = () => compilationUnit()

    lazy val in = { val s = new SourceFileScanner(source); s.init(); s }

    implicit lazy val fresh = new cbc.util.FreshNameCreator()

    // suppress warnings; silent abort on errors
    def warning(offset: Offset, msg: String): Unit = ???
    def abort(msg: String): Unit = ???
    def deprecationWarning(offset: Offset, msg: String): Unit = ???
    def syntaxError(offset: Offset, msg: String): Unit = ???
    def incompleteInputError(msg: String): Unit = ???

    /** the markup parser */
    private[this] lazy val xmlp = new MarkupParser(this, preserveWS = true)
    object symbXMLBuilder extends SymbolicXMLBuilder(this, preserveWS = true)
    def xmlLiteral() : Tree = xmlp.xLiteral
    def xmlLiteralPattern() : Tree = xmlp.xLiteralPattern
  }

  type Location = Int
  final val Local: Location = 0
  final val InBlock: Location = 1
  final val InTemplate: Location = 2

  // These symbols may not yet be loaded (e.g. in the ide) so don't go
  // through definitions to obtain the names.
  lazy val ScalaValueClassNames = Seq(tpnme.AnyVal,
      tpnme.Unit,
      tpnme.Boolean,
      tpnme.Byte,
      tpnme.Short,
      tpnme.Char,
      tpnme.Int,
      tpnme.Long,
      tpnme.Float,
      tpnme.Double)

  import nme.raw

  abstract class Parser extends ParserCommon { parser =>
    val in: Scanner
    def source: SourceFile
    implicit def fresh: FreshNameCreator

    /** Scoping operator used to temporarily look into the future.
     *  Backs up scanner data before evaluating a block and restores it after.
     */
    @inline final def lookingAhead[T](body: => T): T = {
      val saved = new ScannerData {} copyFrom in
      in.nextToken()
      try body finally in copyFrom saved
    }

    /** Perform an operation while peeking ahead.
     *  Pushback if the operation yields an empty tree or blows to pieces.
     */
    @inline def peekingAhead(tree: =>Tree): Tree = {
      @inline def peekahead() = {
        in.prev copyFrom in
        in.nextToken()
      }
      @inline def pushback() = {
        in.next copyFrom in
        in copyFrom in.prev
      }
      peekahead()
      // try it, in case it is recoverable
      val res = try tree catch { case e: Exception => pushback() ; throw e }
      if (res.isEmpty) pushback()
      res
    }

    class ParserTreeBuilder extends TreeBuilder {
      def source = parser.source
      implicit def fresh = parser.fresh
    }
    val treeBuilder = new ParserTreeBuilder
    import treeBuilder.{source => _, fresh => _, _}

    /** whether a non-continuable syntax error has been seen */
    private var lastErrorOffset : Int = -1

    /** The types of the context bounds of type parameters of the surrounding class
     */
    private var classContextBounds: List[Tree] = Nil
    @inline private def savingClassContextBounds[T](op: => T): T = {
      val saved = classContextBounds
      try op
      finally classContextBounds = saved
    }


    /** Are we inside the Scala package? Set for files that start with package scala
     */
    private var inScalaPackage = false
    private var currentPackage = ""
    def resetPackage() {
      inScalaPackage = false
      currentPackage = ""
    }
    private def inScalaRootPackage = inScalaPackage && currentPackage == "scala"

    def parseStartRule: () => Tree

    def parseRule[T](rule: this.type => T): T = {
      val t = rule(this)
      accept(EOF)
      t
    }

    /** This is the general parse entry point.
     */
    def parse(): Tree = parseRule(_.parseStartRule())

    /** These are alternative entry points for repl, script runner, toolbox and parsing in macros.
     */
    def parseStats(): List[Tree] = parseRule(_.templateStats())
    def parseStatsOrPackages(): List[Tree] = parseRule(_.templateOrTopStatSeq())

/* --------------- PLACEHOLDERS ------------------------------------------- */

    /** The implicit parameters introduced by `_` in the current expression.
     *  Parameters appear in reverse order.
     */
    var placeholderParams: List[ValDef] = Nil

    /** The placeholderTypes introduced by `_` in the current type.
     *  Parameters appear in reverse order.
     */
    var placeholderTypes: List[TypeDef] = Nil

    def checkNoEscapingPlaceholders[T](op: => T): T = {
      val savedPlaceholderParams = placeholderParams
      val savedPlaceholderTypes = placeholderTypes
      placeholderParams = List()
      placeholderTypes = List()

      val res = op

      placeholderParams match {
        case vd :: _ =>
          syntaxError(/* vd.pos */ ???, "unbound placeholder parameter", skipIt = false)
          placeholderParams = List()
        case _ =>
      }
      placeholderTypes match {
        case td :: _ =>
          syntaxError(/* td.pos */ ???, "unbound wildcard type", skipIt = false)
          placeholderTypes = List()
        case _ =>
      }
      placeholderParams = savedPlaceholderParams
      placeholderTypes = savedPlaceholderTypes

      res
    }

    def placeholderTypeBoundary(op: => Tree): Tree = {
      val savedPlaceholderTypes = placeholderTypes
      placeholderTypes = List()
      var t = op
      if (!placeholderTypes.isEmpty && t.isInstanceOf[AppliedTypeTree]) {
        t = ExistentialTypeTree(t, placeholderTypes.reverse)
        placeholderTypes = List()
      }
      placeholderTypes = placeholderTypes ::: savedPlaceholderTypes
      t
    }

    def isWildcard(t: Tree): Boolean = t match {
      case Ident(name1) => !placeholderParams.isEmpty && name1 == placeholderParams.head.name
      case Typed(t1, _) => isWildcard(t1)
      case Annotated(t1, _) => isWildcard(t1)
      case _ => false
    }

/* ------------- ERROR HANDLING ------------------------------------------- */

    val assumedClosingParens = mutable.Map(RPAREN -> 0, RBRACKET -> 0, RBRACE -> 0)

    private var inFunReturnType = false
    @inline private def fromWithinReturnType[T](body: => T): T = {
      val saved = inFunReturnType
      inFunReturnType = true
      try body
      finally inFunReturnType = saved
    }

    protected def skip(targetToken: Token) {
      var nparens = 0
      var nbraces = 0
      while (true) {
        in.token match {
          case EOF =>
            return
          case SEMI =>
            if (nparens == 0 && nbraces == 0) return
          case NEWLINE =>
            if (nparens == 0 && nbraces == 0) return
          case NEWLINES =>
            if (nparens == 0 && nbraces == 0) return
          case RPAREN =>
            nparens -= 1
          case RBRACE =>
            if (nbraces == 0) return
            nbraces -= 1
          case LPAREN =>
            nparens += 1
          case LBRACE =>
            nbraces += 1
          case _ =>
        }
        if (targetToken == in.token && nparens == 0 && nbraces == 0) return
        in.nextToken()
      }
    }

    def warning(offset: Offset, msg: String): Unit
    def abort(msg: String): Unit
    def incompleteInputError(msg: String): Unit
    def syntaxError(offset: Offset, msg: String): Unit
    def syntaxError(msg: String, skipIt: Boolean) {
      syntaxError(in.offset, msg, skipIt)
    }
    def syntaxError(offset: Offset, msg: String, skipIt: Boolean) {
      if (offset > lastErrorOffset) {
        syntaxError(offset, msg)
        // no more errors on this token.
        lastErrorOffset = in.offset
      }
      if (skipIt)
        skip(UNDEF)
    }

    def warning(msg: String) { warning(in.offset, msg) }

    def syntaxErrorOrIncomplete(msg: String, skipIt: Boolean) {
      if (in.token == EOF)
        incompleteInputError(msg)
      else
        syntaxError(in.offset, msg, skipIt)
    }
    def syntaxErrorOrIncompleteAnd[T](msg: String, skipIt: Boolean)(and: T): T = {
      syntaxErrorOrIncomplete(msg, skipIt)
      and
    }

    def expectedMsgTemplate(exp: String, fnd: String) = s"$exp expected but $fnd found."
    def expectedMsg(token: Token): String = expectedMsgTemplate(token2string(token), token2string(in.token))

    /** Consume one token of the specified type, or signal an error if it is not there. */
    def accept(token: Token): Offset = {
      val offset = in.offset
      if (in.token != token) {
        syntaxErrorOrIncomplete(expectedMsg(token), skipIt = false)
        if ((token == RPAREN || token == RBRACE || token == RBRACKET))
          if (in.parenBalance(token) + assumedClosingParens(token) < 0)
            assumedClosingParens(token) += 1
          else
            skip(token)
        else
          skip(UNDEF)
      }
      if (in.token == token) in.nextToken()
      offset
    }

    /** {{{
     *  semi = nl {nl} | `;`
     *  nl  = `\n' // where allowed
     *  }}}
     */
    def acceptStatSep(): Unit = in.token match {
      case NEWLINE | NEWLINES => in.nextToken()
      case _                  => accept(SEMI)
    }
    def acceptStatSepOpt() =
      if (!isStatSeqEnd)
        acceptStatSep()

    def errorTypeTree(tree: Tree = EmptyTree) =
      TypeTree() setOriginal Ident(TypeName(s"$$ error type ($tree) $$"))

    def errorTermTree    = newLiteral(null)
    def errorPatternTree = Ident(nme.WILDCARD)

    /** Check that type parameter is not by name or repeated. */
    def checkNotByNameOrVarargs(tpt: Tree) = {
      if (TreeInfo isByNameParamType tpt)
        syntaxError(/* tpt.pos */ ???, "no by-name parameter type allowed here", skipIt = false)
      else if (TreeInfo isRepeatedParamType tpt)
        syntaxError(/* tpt.pos */ ???, "no * parameter type allowed here", skipIt = false)
    }

/* -------------- TOKEN CLASSES ------------------------------------------- */

    def isModifier: Boolean = in.token match {
      case ABSTRACT | FINAL | SEALED | PRIVATE |
           PROTECTED | OVERRIDE | IMPLICIT | LAZY => true
      case _ => false
    }

    def isAnnotation: Boolean = in.token == AT

    def isLocalModifier: Boolean = in.token match {
      case ABSTRACT | FINAL | SEALED | IMPLICIT | LAZY => true
      case _ => false
    }

    def isTemplateIntro: Boolean = in.token match {
      case OBJECT | CASEOBJECT | CLASS | CASECLASS | TRAIT  => true
      case _                                                => false
    }
    def isDclIntro: Boolean = in.token match {
      case VAL | VAR | DEF | TYPE => true
      case _ => false
    }

    def isDefIntro = isTemplateIntro || isDclIntro

    def isNumericLit: Boolean = in.token match {
      case INTLIT | LONGLIT | FLOATLIT | DOUBLELIT => true
      case _ => false
    }

    def isIdentExcept(except: Name) = isIdent && in.name != except
    def isIdentOf(name: Name)       = isIdent && in.name == name

    def isUnaryOp = isIdent && raw.isUnary(in.name)
    def isRawStar = isIdent && in.name == raw.STAR
    def isRawBar  = isIdent && in.name == raw.BAR

    def isIdent = in.token == IDENTIFIER || in.token == BACKQUOTED_IDENT
    def isMacro = in.token == IDENTIFIER && in.name == nme.MACROkw

    def isLiteralToken(token: Token) = token match {
      case CHARLIT | INTLIT | LONGLIT | FLOATLIT | DOUBLELIT |
           STRINGLIT | INTERPOLATIONID | SYMBOLLIT | TRUE | FALSE | NULL => true
      case _                                                        => false
    }
    def isLiteral = isLiteralToken(in.token)

    def isExprIntroToken(token: Token): Boolean = isLiteralToken(token) || (token match {
      case IDENTIFIER | BACKQUOTED_IDENT |
           THIS | SUPER | IF | FOR | NEW | USCORE | TRY | WHILE |
           DO | RETURN | THROW | LPAREN | LBRACE | XMLSTART => true
      case _ => false
    })

    def isExprIntro: Boolean = isExprIntroToken(in.token)

    def isTypeIntroToken(token: Token): Boolean = token match {
      case IDENTIFIER | BACKQUOTED_IDENT | THIS |
           SUPER | USCORE | LPAREN | AT => true
      case _ => false
    }

    def isStatSeqEnd = in.token == RBRACE || in.token == EOF

    def isCaseDefEnd = in.token == RBRACE || in.token == CASE || in.token == EOF

    def isStatSep(token: Token): Boolean =
      token == NEWLINE || token == NEWLINES || token == SEMI

    def isStatSep: Boolean = isStatSep(in.token)


/* --------- COMMENT AND ATTRIBUTE COLLECTION ----------------------------- */

    /** A hook for joining the comment associated with a definition.
     *  Overridden by scaladoc.
     */
    def joinComment(trees: => List[Tree]): List[Tree] = trees

/* ---------- TREE CONSTRUCTION ------------------------------------------- */

    /** Convert tree to formal parameter list. */
    def convertToParams(tree: Tree): List[ValDef] = tree match {
      case Parens(ts) => ts map convertToParam
      case _          => List(convertToParam(tree))
    }

    /** Convert tree to formal parameter. */
    def convertToParam(tree: Tree): ValDef = {
      def removeAsPlaceholder(name: Name) {
        placeholderParams = placeholderParams filter (_.name != name)
      }
      def errorParam = makeParam(nme.ERROR, errorTypeTree())
      tree match {
        case Ident(name) =>
          removeAsPlaceholder(name)
          makeParam(name.toTermName, TypeTree())
        case Typed(Ident(name), tpe) if tpe.isType => // get the ident!
          removeAsPlaceholder(name)
          makeParam(name.toTermName, tpe)
        case _ =>
          syntaxError(/* tree.pos */ ???, "not a legal formal parameter", skipIt = false)
          errorParam
      }
    }

    /** Convert (qual)ident to type identifier. */
    def convertToTypeId(tree: Tree): Tree = {
      convertToTypeName(tree) getOrElse {
        syntaxError(/* tree.pos */ ???, "identifier expected", skipIt = false)
        errorTypeTree()
      }
    }

    /** {{{ part { `sep` part } }}},or if sepFirst is true, {{{ { `sep` part } }}}. */
    final def tokenSeparated[T](separator: Token, sepFirst: Boolean, part: => T): List[T] = {
      val ts = new ListBuffer[T]
      if (!sepFirst)
        ts += part

      while (in.token == separator) {
        in.nextToken()
        ts += part
      }
      ts.toList
    }
    @inline final def commaSeparated[T](part: => T): List[T] = tokenSeparated(COMMA, sepFirst = false, part)
    @inline final def caseSeparated[T](part: => T): List[T] = tokenSeparated(CASE, sepFirst = true, part)
    def readAnnots(part: => Tree): List[Tree] = tokenSeparated(AT, sepFirst = true, part)

/* --------- OPERAND/OPERATOR STACK --------------------------------------- */

    /** Modes for infix types. */
    object InfixMode extends Enumeration {
      val FirstOp, LeftOp, RightOp = Value
    }

    var opstack: List[OpInfo] = Nil

    @deprecated("Use `cbc.Precedence`", "2.11.0")
    def precedence(operator: Name): Int = Precedence(operator.toString).level

    private def opHead = opstack.head
    private def headPrecedence = opHead.precedence
    private def popOpInfo(): OpInfo = try opHead finally opstack = opstack.tail
    private def pushOpInfo(top: Tree): Unit = {
      val name   = in.name
      val offset = in.offset
      ident()
      val targs = if (in.token == LBRACKET) exprTypeArgs() else Nil
      val opinfo = OpInfo(top, name, targs, offset)
      opstack ::= opinfo
    }

    def checkHeadAssoc(leftAssoc: Boolean) = checkAssoc(opHead.offset, opHead.operator, leftAssoc)
    def checkAssoc(offset: Offset, op: Name, leftAssoc: Boolean) = (
      if (TreeInfo.isLeftAssoc(op) != leftAssoc)
        syntaxError(offset, "left- and right-associative operators with same precedence may not be mixed", skipIt = false)
    )

    def finishPostfixOp(start: Int, base: List[OpInfo], opinfo: OpInfo): Tree = {
      if (opinfo.targs.nonEmpty)
        syntaxError(opinfo.offset, "type application is not allowed for postfix operators")

      val od = stripParens(reduceExprStack(base, opinfo.lhs))
      makePostfixSelect(od, opinfo.operator)
    }

    def finishBinaryOp(isExpr: Boolean, opinfo: OpInfo, rhs: Tree): Tree = {
      import opinfo._
      makeBinop(isExpr, lhs, operator, rhs, opinfo.targs)
    }

    def reduceExprStack(base: List[OpInfo], top: Tree): Tree    = reduceStack(isExpr = true, base, top)
    def reducePatternStack(base: List[OpInfo], top: Tree): Tree = reduceStack(isExpr = false, base, top)

    def reduceStack(isExpr: Boolean, base: List[OpInfo], top: Tree): Tree = {
      val opPrecedence = if (isIdent) Precedence(in.name.toString) else Precedence(0)
      val leftAssoc    = !isIdent || (TreeInfo isLeftAssoc in.name)

      reduceStack(isExpr, base, top, opPrecedence, leftAssoc)
    }

    def reduceStack(isExpr: Boolean, base: List[OpInfo], top: Tree, opPrecedence: Precedence, leftAssoc: Boolean): Tree = {
      def isDone          = opstack == base
      def lowerPrecedence = !isDone && (opPrecedence < headPrecedence)
      def samePrecedence  = !isDone && (opPrecedence == headPrecedence)
      def canReduce       = lowerPrecedence || leftAssoc && samePrecedence

      if (samePrecedence)
        checkHeadAssoc(leftAssoc)

      def loop(top: Tree): Tree = if (canReduce) {
        val info = popOpInfo()
        if (!isExpr && info.targs.nonEmpty) {
          syntaxError(info.offset, "type application is not allowed in pattern")
          info.targs.foreach(errorTypeTree(_))
        }
        loop(finishBinaryOp(isExpr, info, top))
      } else top

      loop(top)
    }

/* -------- IDENTIFIERS AND LITERALS ------------------------------------------- */

    /** Methods which implicitly propagate the context in which they were
     *  called: either in a pattern context or not.  Formerly, this was
     *  threaded through numerous methods as boolean isPattern.
     */
    trait PatternContextSensitive {
      /** {{{
       *  ArgType       ::=  Type
       *  }}}
       */
      def argType(): Tree
      def functionArgType(): Tree

      private def tupleInfixType(start: Offset) = {
        in.nextToken()
        if (in.token == RPAREN) {
          in.nextToken()
          accept(ARROW)
          makeFunctionTypeTree(Nil, typ())
        }
        else {
          val ts = functionTypes()
          accept(RPAREN)
          if (in.token == ARROW) {
            in.skipToken()
            makeFunctionTypeTree(ts, typ())
          } else {
            ts foreach checkNotByNameOrVarargs
            val tuple = makeTupleType(ts)
            infixTypeRest(
              compoundTypeRest(
                annotTypeRest(
                  simpleTypeRest(
                    tuple))),
              InfixMode.FirstOp
            )
          }
        }
      }
      private def makeExistentialTypeTree(t: Tree) = {
        // EmptyTrees in the result of refinement() stand for parse errors
        // so it's okay for us to filter them out here
        ExistentialTypeTree(t, refinement() flatMap {
          case t @ TypeDef(_, _, _, TypeBoundsTree(_, _)) => Some(t)
          case t @ ValDef(_, _, _, EmptyTree) => Some(t)
          case EmptyTree => None
          case _ => syntaxError(/* t.pos */ ???, "not a legal existential clause", skipIt = false); None
        })
      }

      /** {{{
       *  Type ::= InfixType `=>' Type
       *         | `(' [`=>' Type] `)' `=>' Type
       *         | InfixType [ExistentialClause]
       *  ExistentialClause ::= forSome `{' ExistentialDcl {semi ExistentialDcl}} `}'
       *  ExistentialDcl    ::= type TypeDcl | val ValDcl
       *  }}}
       */
      def typ(): Tree = placeholderTypeBoundary {
        val start = in.offset
        val t =
          if (in.token == LPAREN) tupleInfixType(start)
          else infixType(InfixMode.FirstOp)

        in.token match {
          case ARROW    => in.skipToken(); makeFunctionTypeTree(List(t), typ())
          case FORSOME  => in.skipToken(); makeExistentialTypeTree(t)
          case _        => t
        }
      }

      /** {{{
       *  TypeArgs    ::= `[' ArgType {`,' ArgType} `]'
       *  }}}
       */
      def typeArgs(): List[Tree] = inBrackets(types())

      /** {{{
       *  AnnotType        ::=  SimpleType {Annotation}
       *  }}}
       */
      def annotType(): Tree = placeholderTypeBoundary { annotTypeRest(simpleType()) }

      /** {{{
       *  SimpleType       ::=  SimpleType TypeArgs
       *                     |  SimpleType `#' Id
       *                     |  StableId
       *                     |  Path `.' type
       *                     |  `(' Types `)'
       *                     |  WildcardType
       *  }}}
       */
      def simpleType(): Tree = {
        val start = in.offset
        simpleTypeRest(in.token match {
          case LPAREN   => makeTupleType(inParens(types()))
          case USCORE   => wildcardType(in.skipToken())
          case _        =>
            path(thisOK = false, typeOK = true) match {
              case r @ SingletonTypeTree(_) => r
              case r => convertToTypeId(r)
            }
        })
      }

      private def typeProjection(t: Tree): Tree = {
        in.skipToken()
        val name       = identForType(skipIt = false)
        SelectFromTypeTree(t, name)
      }
      def simpleTypeRest(t: Tree): Tree = in.token match {
        case HASH     => simpleTypeRest(typeProjection(t))
        case LBRACKET => simpleTypeRest(AppliedTypeTree(t, typeArgs()))
        case _        => t
      }

      /** {{{
       *  CompoundType ::= AnnotType {with AnnotType} [Refinement]
       *                |  Refinement
       *  }}}
       */
      def compoundType(): Tree = compoundTypeRest(
        if (in.token == LBRACE) scalaAnyRefConstr
        else annotType()
      )

      def compoundTypeRest(t: Tree): Tree = {
        val ts = new ListBuffer[Tree] += t
        while (in.token == WITH) {
          in.nextToken()
          ts += annotType()
        }
        newLineOptWhenFollowedBy(LBRACE)
        val types         = ts.toList
        val braceOffset   = in.offset
        val hasRefinement = in.token == LBRACE
        val refinements   = if (hasRefinement) refinement() else Nil
        // Warn if they are attempting to refine Unit; we can't be certain it's
        // scala.Unit they're refining because at this point all we have is an
        // identifier, but at a later stage we lose the ability to tell an empty
        // refinement from no refinement at all.  See bug #284.
        if (hasRefinement) types match {
          case Ident(name) :: Nil if name endsWith "Unit" => warning(braceOffset, "Detected apparent refinement of Unit; are you missing an '=' sign?")
          case _                                          =>
        }
        // The second case includes an empty refinement - refinements is empty, but
        // it still gets a CompoundTypeTree.
        ts.toList match {
          case tp :: Nil if !hasRefinement => tp  // single type, no refinement, already positioned
          case tps                         => CompoundTypeTree(Template(tps, noSelfType, refinements))
        }
      }

      def infixTypeRest(t: Tree, mode: InfixMode.Value): Tree = {
        if (isIdent && in.name != nme.STAR) {
          val opOffset = in.offset
          val leftAssoc = TreeInfo.isLeftAssoc(in.name)
          if (mode != InfixMode.FirstOp) checkAssoc(opOffset, in.name, leftAssoc = mode == InfixMode.LeftOp)
          val op = identForType()
          val tycon = Ident(op)
          newLineOptWhenFollowing(isTypeIntroToken)
          def mkOp(t1: Tree) = AppliedTypeTree(tycon, List(t, t1))
          if (leftAssoc)
            infixTypeRest(mkOp(compoundType()), InfixMode.LeftOp)
          else
            mkOp(infixType(InfixMode.RightOp))
        } else t
      }

      /** {{{
       *  InfixType ::= CompoundType {id [nl] CompoundType}
       *  }}}
       */
      def infixType(mode: InfixMode.Value): Tree =
        placeholderTypeBoundary { infixTypeRest(compoundType(), mode) }

      /** {{{
       *  Types ::= Type {`,' Type}
       *  }}}
       */
      def types(): List[Tree] = commaSeparated(argType())
      def functionTypes(): List[Tree] = commaSeparated(functionArgType())
    }

    /** Assumed (provisionally) to be TermNames. */
    def ident(skipIt: Boolean): Name = (
      if (isIdent) {
        val name = in.name.encode
        in.nextToken()
        name
      }
      else syntaxErrorOrIncompleteAnd(expectedMsg(IDENTIFIER), skipIt)(nme.ERROR)
    )

    def ident(): Name = ident(skipIt = true)
    def rawIdent(): Name = try in.name finally in.nextToken()

    /** For when it's known already to be a type name. */
    def identForType(): TypeName = ident().toTypeName
    def identForType(skipIt: Boolean): TypeName = ident(skipIt).toTypeName

    def identOrMacro(): Name = if (isMacro) rawIdent() else ident()

    def selector(t: Tree): Tree = {
      val point = in.offset
      if (t != EmptyTree)
        Select(t, ident(skipIt = false))
      else
        errorTermTree // has already been reported
    }

    /** {{{
     *  Path       ::= StableId
     *              |  [Ident `.'] this
     *  AnnotType ::= Path [`.' type]
     *  }}}
     */
    def path(thisOK: Boolean, typeOK: Boolean): Tree = {
      val start = in.offset
      var t: Tree = null
      if (in.token == THIS) {
        in.nextToken()
        t = This(tpnme.EMPTY)
        if (!thisOK || in.token == DOT) {
          t = selectors(t, typeOK, accept(DOT))
        }
      } else if (in.token == SUPER) {
        in.nextToken()
        t = Super(This(tpnme.EMPTY), mixinQualifierOpt())
        accept(DOT)
        t = selector(t)
        if (in.token == DOT) t = selectors(t, typeOK, in.skipToken())
      } else {
        val tok = in.token
        val name = ident()
        t = {
          val id = Ident(name)
          if (tok == BACKQUOTED_IDENT) id.isBackquoted = true
          id
        }
        if (in.token == DOT) {
          val dotOffset = in.skipToken()
          if (in.token == THIS) {
            in.nextToken()
            t = This(name.toTypeName)
            if (!thisOK || in.token == DOT)
              t = selectors(t, typeOK, accept(DOT))
          } else if (in.token == SUPER) {
            in.nextToken()
            t = Super(This(name.toTypeName), mixinQualifierOpt())
            accept(DOT)
            t = selector(t)
            if (in.token == DOT) t = selectors(t, typeOK, in.skipToken())
          } else {
            t = selectors(t, typeOK, dotOffset)
          }
        }
      }
      t
    }

    def selectors(t: Tree, typeOK: Boolean, dotOffset: Offset): Tree =
      if (typeOK && in.token == TYPE) {
        in.nextToken()
        SingletonTypeTree(t)
      }
      else {
        val t1 = selector(t)
        if (in.token == DOT) { selectors(t1, typeOK, in.skipToken()) }
        else t1
      }

    /** {{{
    *   MixinQualifier ::= `[' Id `]'
    *   }}}
    */
    def mixinQualifierOpt(): TypeName =
      if (in.token == LBRACKET) inBrackets(identForType())
      else tpnme.EMPTY

    /** {{{
     *  StableId ::= Id
     *            |  Path `.' Id
     *            |  [id `.'] super [`[' id `]']`.' id
     *  }}}
     */
    def stableId(): Tree =
      path(thisOK = false, typeOK = false)

    /** {{{
    *   QualId ::= Id {`.' Id}
    *   }}}
    */
    def qualId(): Tree = {
      val start = in.offset
      val id = Ident(ident())
      if (in.token == DOT) { selectors(id, typeOK = false, in.skipToken()) }
      else id
    }
    /** Calls `qualId()` and manages some package state. */
    private def pkgQualId() = {
      if (in.token == IDENTIFIER && in.name.encode == nme.scala_)
        inScalaPackage = true

      val pkg = qualId()
      newLineOptWhenFollowedBy(LBRACE)

      if (currentPackage == "") currentPackage = pkg.toString
      else currentPackage = currentPackage + "." + pkg

      pkg
    }

    /** {{{
     *  SimpleExpr    ::= literal
     *                  | symbol
     *                  | null
     *  }}}
     */
    def literal(isNegated: Boolean = false, inPattern: Boolean = false, start: Offset = in.offset): Tree = {
      def finish(value: Any): Tree = try newLiteral(value) finally in.nextToken()
      if (in.token == SYMBOLLIT)
        Apply(scalaDot(nme.Symbol), List(finish(in.strVal)))
      else if (in.token == INTERPOLATIONID)
        interpolatedString(inPattern = inPattern)
      else finish(in.token match {
        case CHARLIT                => in.charVal
        case INTLIT                 => in.intVal(isNegated).toInt
        case LONGLIT                => in.intVal(isNegated)
        case FLOATLIT               => in.floatVal(isNegated).toFloat
        case DOUBLELIT              => in.floatVal(isNegated)
        case STRINGLIT | STRINGPART => in.strVal.intern()
        case TRUE                   => true
        case FALSE                  => false
        case NULL                   => null
        case _                      => syntaxErrorOrIncompleteAnd("illegal literal", skipIt = true)(null)
      })
    }

    /** Handle placeholder syntax.
     *  If evaluating the tree produces placeholders, then make it a function.
     */
    private def withPlaceholders(tree: =>Tree, isAny: Boolean): Tree = {
      val savedPlaceholderParams = placeholderParams
      placeholderParams = List()
      var res = tree
      if (placeholderParams.nonEmpty && !isWildcard(res)) {
        res = Function(placeholderParams.reverse, res)
        if (isAny) placeholderParams foreach (_.tpt match {
          case tpt @ TypeTree() => TreeGen.scalaAny
          case _                => // some ascription
        })
        placeholderParams = List()
      }
      placeholderParams = placeholderParams ::: savedPlaceholderParams
      res
    }

    /** Consume a USCORE and create a fresh synthetic placeholder param. */
    private def freshPlaceholder(): Tree = {
      val start = in.offset
      val pname = freshTermName()
      in.nextToken()
      val id = Ident(pname)
      val param = TreeGen.mkSyntheticParam(pname.toTermName)
      placeholderParams = param :: placeholderParams
      id
    }

    private def interpolatedString(inPattern: Boolean): Tree = {
      def errpolation() = syntaxErrorOrIncompleteAnd("error in interpolated string: identifier or block expected",
                                                     skipIt = true)(EmptyTree)
      // Like Swiss cheese, with holes
      def stringCheese: Tree = {
        val start = in.offset
        val interpolator = in.name.encoded // ident() for INTERPOLATIONID

        val partsBuf = new ListBuffer[Tree]
        val exprBuf = new ListBuffer[Tree]
        in.nextToken()
        while (in.token == STRINGPART) {
          partsBuf += literal()
          exprBuf += (
            if (inPattern) dropAnyBraces(pattern())
            else in.token match {
              case IDENTIFIER => (Ident(ident()))
              //case USCORE   => freshPlaceholder()  // ifonly etapolation
              case LBRACE     => expr()              // dropAnyBraces(expr0(Local))
              case THIS       => in.nextToken(); (This(tpnme.EMPTY))
              case _          => errpolation()
            }
          )
        }
        if (in.token == STRINGLIT) partsBuf += literal()

        val t1 = { Ident(nme.StringContext) }
        val t2 = { Apply(t1, partsBuf.toList) }
        val t3 = Select(t2, interpolator)
        Apply(t3, exprBuf.toList)
      }
      if (inPattern) stringCheese
      else withPlaceholders(stringCheese, isAny = true) // strinterpolator params are Any* by definition
    }

/* ------------- NEW LINES ------------------------------------------------- */

    def newLineOpt() {
      if (in.token == NEWLINE) in.nextToken()
    }

    def newLinesOpt() {
      if (in.token == NEWLINE || in.token == NEWLINES)
        in.nextToken()
    }

    def newLineOptWhenFollowedBy(token: Offset) {
      // note: next is defined here because current == NEWLINE
      if (in.token == NEWLINE && in.next.token == token) newLineOpt()
    }

    def newLineOptWhenFollowing(p: Token => Boolean) {
      // note: next is defined here because current == NEWLINE
      if (in.token == NEWLINE && p(in.next.token)) newLineOpt()
    }

/* ------------- TYPES ---------------------------------------------------- */

    /** {{{
     *  TypedOpt ::= [`:' Type]
     *  }}}
     */
    def typedOpt(): Tree =
      if (in.token == COLON) { in.nextToken(); typ() }
      else TypeTree()

    def typeOrInfixType(location: Location): Tree =
      if (location == Local) typ()
      else startInfixType()

    def annotTypeRest(t: Tree): Tree =
      (t /: annotations(skipNewLines = false)) (makeAnnotated)

    /** {{{
     *  WildcardType ::= `_' TypeBounds
     *  }}}
     */
    def wildcardType(start: Offset) = {
      val pname = freshTypeName("_$")
      val t = (Ident(pname))
      val bounds = typeBounds()
      val param = { makeSyntheticTypeParam(pname, bounds) }
      placeholderTypes = param :: placeholderTypes
      t
    }

/* ----------- EXPRESSIONS ------------------------------------------------ */

    def condExpr(): Tree = {
      if (in.token == LPAREN) {
        in.nextToken()
        val r = expr()
        accept(RPAREN)
        r
      } else {
        accept(LPAREN)
        newLiteral(true)
      }
    }

    /* hook for IDE, unlike expression can be stubbed
     * don't use for any tree that can be inspected in the parser!
     */
    def statement(location: Location): Tree = expr(location) // !!! still needed?

    /** {{{
     *  Expr       ::= (Bindings | [`implicit'] Id | `_')  `=>' Expr
     *               | Expr1
     *  ResultExpr ::= (Bindings | Id `:' CompoundType) `=>' Block
     *               | Expr1
     *  Expr1      ::= if `(' Expr `)' {nl} Expr [[semi] else Expr]
     *               | try (`{' Block `}' | Expr) [catch `{' CaseClauses `}'] [finally Expr]
     *               | while `(' Expr `)' {nl} Expr
     *               | do Expr [semi] while `(' Expr `)'
     *               | for (`(' Enumerators `)' | `{' Enumerators `}') {nl} [yield] Expr
     *               | throw Expr
     *               | return [Expr]
     *               | [SimpleExpr `.'] Id `=' Expr
     *               | SimpleExpr1 ArgumentExprs `=' Expr
     *               | PostfixExpr Ascription
     *               | PostfixExpr match `{' CaseClauses `}'
     *  Bindings   ::= `(' [Binding {`,' Binding}] `)'
     *  Binding    ::= (Id | `_') [`:' Type]
     *  Ascription ::= `:' CompoundType
     *               | `:' Annotation {Annotation}
     *               | `:' `_' `*'
     *  }}}
     */
    def expr(): Tree = expr(Local)

    def expr(location: Location): Tree = withPlaceholders(expr0(location), isAny = false)

    def expr0(location: Location): Tree = (in.token: @scala.annotation.switch) match {
      case IF =>
        in.skipToken()
        val cond = condExpr()
        newLinesOpt()
        val thenp = expr()
        val elsep = if (in.token == ELSE) { in.nextToken(); expr() }
                    else literalUnit
        If(cond, thenp, elsep)
      case TRY =>
        in.skipToken()
        val body = in.token match {
          case LBRACE => inBracesOrUnit(block())
          case LPAREN => inParensOrUnit(expr())
          case _ => expr()
        }
        def catchFromExpr() = List(makeCatchFromExpr(expr()))
        val catches: List[CaseDef] =
          if (in.token != CATCH) Nil
          else {
            in.nextToken()
            if (in.token != LBRACE) catchFromExpr()
            else inBracesOrNil {
              if (in.token == CASE) caseClauses()
              else catchFromExpr()
            }
          }
        val finalizer = in.token match {
          case FINALLY => in.nextToken(); expr()
          case _ => EmptyTree
        }
        Try(body, catches, finalizer)
      case WHILE =>
        def parseWhile = {
          val start = in.offset
          in.skipToken()
          val cond = condExpr()
          newLinesOpt()
          val body = expr()
          makeWhile(start, cond, body)
        }
        parseWhile
      case DO =>
        in.skipToken()
        val lname: Name = freshTermName(nme.DO_WHILE_PREFIX)
        val body = expr()
        if (isStatSep) in.nextToken()
        accept(WHILE)
        val cond = condExpr()
        makeDoWhile(lname.toTermName, body, cond)
      case FOR =>
        val start = in.skipToken()
        val enums =
          if (in.token == LBRACE) inBracesOrNil(enumerators())
          else inParensOrNil(enumerators())
        newLinesOpt()
        if (in.token == YIELD) {
          in.nextToken()
          TreeGen.mkFor(enums, TreeGen.Yield(expr()))
        } else {
          TreeGen.mkFor(enums, expr())
        }
      case RETURN =>
        in.skipToken()
        Return(if (isExprIntro) expr() else literalUnit)
      case THROW =>
        in.skipToken()
        Throw(expr())
      case IMPLICIT =>
        implicitClosure(in.skipToken(), location)
      case _ =>
        def parseOther = {
          var t = postfixExpr()
          if (in.token == EQUALS) {
            t match {
              case Ident(_) | Select(_, _) | Apply(_, _) =>
                in.skipToken()
                t = { TreeGen.mkAssign(t, expr()) }
              case _ =>
            }
          } else if (in.token == COLON) {
            t = stripParens(t)
            val colonPos = in.skipToken()
            if (in.token == USCORE) {
              //todo: need to handle case where USCORE is a wildcard in a type
              val uscorePos = in.skipToken()
              if (isIdent && in.name == nme.STAR) {
                in.nextToken()
                t = {
                  Typed(t, { Ident(tpnme.WILDCARD_STAR) })
                }
              } else {
                syntaxErrorOrIncomplete("`*' expected", skipIt = true)
              }
            } else if (isAnnotation) {
              t = (t /: annotations(skipNewLines = false))(makeAnnotated)
            } else {
              t = {
                val tpt = typeOrInfixType(location)
                if (isWildcard(t))
                  (placeholderParams: @unchecked) match {
                    case (vd @ ValDef(mods, name, _, _)) :: rest =>
                      placeholderParams = treeCopy.ValDef(vd, mods, name, tpt.duplicate, EmptyTree) :: rest
                  }
                // this does not correspond to syntax, but is necessary to
                // accept closures. We might restrict closures to be between {...} only.
                Typed(t, tpt)
              }
            }
          } else if (in.token == MATCH) {
            in.skipToken()
            t = (Match(stripParens(t), inBracesOrNil(caseClauses())))
          }
          // in order to allow anonymous functions as statements (as opposed to expressions) inside
          // templates, we have to disambiguate them from self type declarations - bug #1565
          // The case still missed is unparenthesized single argument, like "x: Int => x + 1", which
          // may be impossible to distinguish from a self-type and so remains an error.  (See #1564)
          def lhsIsTypedParamList() = t match {
            case Parens(xs) if xs.forall(isTypedParam) => true
            case _ => false
          }
          if (in.token == ARROW && (location != InTemplate || lhsIsTypedParamList)) {
            in.skipToken()
            t = {
              Function(convertToParams(t), if (location != InBlock) expr() else block())
            }
          }
          stripParens(t)
        }
        parseOther
    }

    def isTypedParam(t: Tree) = t.isInstanceOf[Typed]

    /** {{{
     *  Expr ::= implicit Id => Expr
     *  }}}
     */

    def implicitClosure(start: Offset, location: Location): Tree = {
      val param0 = convertToParam {
        Ident(ident()) match {
          case expr if in.token == COLON  =>
            in.nextToken() ; Typed(expr, typeOrInfixType(location))
          case expr => expr
        }
      }
      val param = copyValDef(param0)(mods = param0.mods | Flags.IMPLICIT)
      accept(ARROW)
      Function(List(param), if (location != InBlock) expr() else block())
    }

    /** {{{
     *  PostfixExpr   ::= InfixExpr [Id [nl]]
     *  InfixExpr     ::= PrefixExpr
     *                  | InfixExpr Id [nl] InfixExpr
     *  }}}
     */
    def postfixExpr(): Tree = {
      val start = in.offset
      val base  = opstack

      def loop(top: Tree): Tree = if (!isIdent) top else {
        pushOpInfo(reduceExprStack(base, top))
        newLineOptWhenFollowing(isExprIntroToken)
        if (isExprIntro)
          prefixExpr() match {
            case EmptyTree => reduceExprStack(base, top)
            case next      => loop(next)
          }
        else finishPostfixOp(start, base, popOpInfo())
      }

      reduceExprStack(base, loop(prefixExpr()))
    }

    /** {{{
     *  PrefixExpr   ::= [`-' | `+' | `~' | `!' | `&'] SimpleExpr
     *  }}}
     */
    def prefixExpr(): Tree = {
      if (isUnaryOp) {
        {
          val name = nme.toUnaryName(rawIdent().toTermName)
          if (name == nme.UNARY_- && isNumericLit)
            simpleExprRest(literal(isNegated = true), canApply = true)
          else
            Select(stripParens(simpleExpr()), name)
        }
      }
      else simpleExpr()
    }
    def xmlLiteral(): Tree

    /** {{{
     *  SimpleExpr    ::= new (ClassTemplate | TemplateBody)
     *                  |  BlockExpr
     *                  |  SimpleExpr1 [`_']
     *  SimpleExpr1   ::= literal
     *                  |  xLiteral
     *                  |  Path
     *                  |  `(' [Exprs] `)'
     *                  |  SimpleExpr `.' Id
     *                  |  SimpleExpr TypeArgs
     *                  |  SimpleExpr1 ArgumentExprs
     *  }}}
     */
    def simpleExpr(): Tree = {
      var canApply = true
      val t =
        if (isLiteral) literal()
        else in.token match {
          case XMLSTART =>
            xmlLiteral()
          case IDENTIFIER | BACKQUOTED_IDENT | THIS | SUPER =>
            path(thisOK = true, typeOK = false)
          case USCORE =>
            freshPlaceholder()
          case LPAREN =>
            makeParens(commaSeparated(expr()))
          case LBRACE =>
            canApply = false
            blockExpr()
          case NEW =>
            canApply = false
            val nstart = in.skipToken()
            val tstart = in.offset
            val (parents, self, stats) = template()
            TreeGen.mkNew(parents, self, stats)
          case _ =>
            syntaxErrorOrIncompleteAnd("illegal start of simple expression", skipIt = true)(errorTermTree)
        }
      simpleExprRest(t, canApply = canApply)
    }

    def simpleExprRest(t: Tree, canApply: Boolean): Tree = {
      if (canApply) newLineOptWhenFollowedBy(LBRACE)
      in.token match {
        case DOT =>
          in.nextToken()
          simpleExprRest(selector(stripParens(t)), canApply = true)
        case LBRACKET =>
          val t1 = stripParens(t)
          t1 match {
            case Ident(_) | Select(_, _) | Apply(_, _) =>
              var app: Tree = t1
              while (in.token == LBRACKET)
                app = TypeApply(app, exprTypeArgs())

              simpleExprRest(app, canApply = true)
            case _ =>
              t1
          }
        case LPAREN | LBRACE if (canApply) =>
          val app = {
            // look for anonymous function application like (f _)(x) and
            // translate to (f _).apply(x), bug #460
            val sel = t match {
              case Parens(List(Typed(_, _: Function))) =>
                Select(stripParens(t), nme.apply)
              case _ =>
                stripParens(t)
            }
            Apply(sel, argumentExprs())
          }
          simpleExprRest(app, canApply = true)
        case USCORE =>
          in.skipToken()
          Typed(stripParens(t), Function(Nil, EmptyTree))
        case _ =>
          t
      }
    }

    /** {{{
     *  ArgumentExprs ::= `(' [Exprs] `)'
     *                  | [nl] BlockExpr
     *  }}}
     */
    def argumentExprs(): List[Tree] = {
      def args(): List[Tree] = commaSeparated(
        if (isIdent) TreeInfo.assignmentToMaybeNamedArg(expr()) else expr()
      )
      in.token match {
        case LBRACE   => List(blockExpr())
        case LPAREN   => inParens(if (in.token == RPAREN) Nil else args())
        case _        => Nil
      }
    }
    /** A succession of argument lists. */
    def multipleArgumentExprs(): List[List[Tree]] = {
      if (in.token != LPAREN) Nil
      else argumentExprs() :: multipleArgumentExprs()
    }

    /** {{{
     *  BlockExpr ::= `{' (CaseClauses | Block) `}'
     *  }}}
     */
    def blockExpr(): Tree = {
      inBraces {
        if (in.token == CASE) Match(EmptyTree, caseClauses())
        else block()
      }
    }

    /** {{{
     *  Block ::= BlockStatSeq
     *  }}}
     *  @note  Return tree does not carry position.
     */
    def block(): Tree = makeBlock(blockStatSeq())

    def caseClause(): CaseDef =
      makeCaseDef(pattern(), guard(), caseBlock())

    /** {{{
     *  CaseClauses ::= CaseClause {CaseClause}
     *  CaseClause  ::= case Pattern [Guard] `=>' Block
     *  }}}
     */
    def caseClauses(): List[CaseDef] = {
      val cases = caseSeparated { caseClause() }
      if (cases.isEmpty)  // trigger error if there are no cases
        accept(CASE)

      cases
    }

    // IDE HOOK (so we can memoize case blocks) // needed?
    def caseBlock(): Tree = {
      accept(ARROW)
      block()
    }

    /** {{{
     *  Guard ::= if PostfixExpr
     *  }}}
     */
    def guard(): Tree =
      if (in.token == IF) { in.nextToken(); stripParens(postfixExpr()) }
      else EmptyTree

    /** {{{
     *  Enumerators ::= Generator {semi Enumerator}
     *  Enumerator  ::=  Generator
     *                |  Guard
     *                |  val Pattern1 `=' Expr
     *  }}}
     */
    def enumerators(): List[Tree] = {
      val enums = new ListBuffer[Tree]
      enums ++= enumerator(isFirst = true)
      while (isStatSep) {
        in.nextToken()
        enums ++= enumerator(isFirst = false)
      }
      enums.toList
    }

    def enumerator(isFirst: Boolean, allowNestedIf: Boolean = true): List[Tree] =
      if (in.token == IF && !isFirst) makeFilter(in.offset, guard()) :: Nil
      else generator(!isFirst, allowNestedIf)

    /** {{{
     *  Generator ::= Pattern1 (`<-' | `=') Expr [Guard]
     *  }}}
     */
    def generator(eqOK: Boolean, allowNestedIf: Boolean = true): List[Tree] = {
      val start  = in.offset
      val hasVal = in.token == VAL
      if (hasVal)
        in.nextToken()

      val pat   = noSeq.pattern1()
      val point = in.offset
      val hasEq = in.token == EQUALS

      if (hasVal) {
        if (hasEq) deprecationWarning(in.offset, "val keyword in for comprehension is deprecated")
        else syntaxError(in.offset, "val in for comprehension must be followed by assignment")
      }

      if (hasEq && eqOK) in.nextToken()
      else accept(LARROW)
      val rhs = expr()

      def loop(): List[Tree] =
        if (in.token != IF) Nil
        else makeFilter(in.offset, guard()) :: loop()

      val tail =
        if (allowNestedIf) loop()
        else Nil

      // why max? IDE stress tests have shown that lastOffset could be less than start,
      // I guess this happens if instead if a for-expression we sit on a closing paren.
      TreeGen.mkGenerator(pat, hasEq, rhs) :: tail
    }

    def makeFilter(start: Offset, tree: Tree) = TreeGen.Filter(tree)

/* -------- PATTERNS ------------------------------------------- */

    /** Methods which implicitly propagate whether the initial call took
     *  place in a context where sequences are allowed.  Formerly, this
     *  was threaded through methods as boolean seqOK.
     */
    trait SeqContextSensitive extends PatternContextSensitive {
      // is a sequence pattern _* allowed?
      def isSequenceOK: Boolean

      // are we in an XML pattern?
      def isXML: Boolean = false

      def functionArgType(): Tree = argType()
      def argType(): Tree = {
        val start = in.offset
        in.token match {
          case USCORE =>
            in.nextToken()
            if (in.token == SUBTYPE || in.token == SUPERTYPE) wildcardType(start)
            else Bind(tpnme.WILDCARD, EmptyTree)
          case _ =>
            typ() match {
              case Ident(name: TypeName) if nme.isVariableName(name) =>
                Bind(name, EmptyTree)
              case t => t
            }
        }
      }

      /** {{{
       *  Patterns ::= Pattern { `,' Pattern }
       *  SeqPatterns ::= SeqPattern { `,' SeqPattern }
       *  }}}
       */
      def patterns(): List[Tree] = commaSeparated(pattern())

      /** {{{
       *  Pattern  ::=  Pattern1 { `|' Pattern1 }
       *  SeqPattern ::= SeqPattern1 { `|' SeqPattern1 }
       *  }}}
       */
      def pattern(): Tree = {
        val start = in.offset
        def loop(): List[Tree] = pattern1() :: {
          if (isRawBar) { in.nextToken() ; loop() }
          else Nil
        }
        loop() match {
          case pat :: Nil => pat
          case xs         => makeAlternative(xs)
        }
      }

      /** {{{
       *  Pattern1    ::= varid `:' TypePat
       *                |  `_' `:' TypePat
       *                |  Pattern2
       *  SeqPattern1 ::= varid `:' TypePat
       *                |  `_' `:' TypePat
       *                |  [SeqPattern2]
       *  }}}
       */
      def pattern1(): Tree = pattern2() match {
        case p @ Ident(name) if in.token == COLON =>
          if (TreeInfo.isVarPattern(p)) {
            in.skipToken()
            Typed(p, compoundType())
          } else {
            syntaxError(in.offset, "Pattern variables must start with a lower-case letter. (SLS 8.1.1.)")
            p
          }
        case p => p
      }

      /** {{{
       *  Pattern2    ::=  varid [ @ Pattern3 ]
       *                |   Pattern3
       *  SeqPattern2 ::=  varid [ @ SeqPattern3 ]
       *                |   SeqPattern3
       *  }}}
       */
      def pattern2(): Tree = {
        val p = pattern3()

        if (in.token != AT) p
        else p match {
          case Ident(nme.WILDCARD) =>
            in.nextToken()
            pattern3()
          case Ident(name) if TreeInfo.isVarPattern(p) =>
            in.nextToken()
            Bind(name, pattern3())
          case _ => p
        }
      }

      /** {{{
       *  Pattern3    ::= SimplePattern
       *                |  SimplePattern {Id [nl] SimplePattern}
       *  }}}
       */
      def pattern3(): Tree = {
        val top = simplePattern(badPattern3)
        val base = opstack
        // See SI-3189, SI-4832 for motivation. Cf SI-3480 for counter-motivation.
        def isCloseDelim = in.token match {
          case RBRACE => isXML
          case RPAREN => !isXML
          case _      => false
        }
        def checkWildStar: Tree = top match {
          case Ident(nme.WILDCARD) if isSequenceOK && isRawStar => peekingAhead (
            if (isCloseDelim) Star(stripParens(top))
            else EmptyTree
          )
          case _ => EmptyTree
        }
        def loop(top: Tree): Tree = reducePatternStack(base, top) match {
          case next if isIdentExcept(raw.BAR) => pushOpInfo(next) ; loop(simplePattern(badPattern3))
          case next                           => next
        }
        checkWildStar orElse stripParens(loop(top))
      }

      def badPattern3(): Tree = {
        def isComma                = in.token == COMMA
        def isDelimiter            = in.token == RPAREN || in.token == RBRACE
        def isCommaOrDelimiter     = isComma || isDelimiter
        val (isUnderscore, isStar) = opstack match {
          case OpInfo(Ident(nme.WILDCARD), nme.STAR, _, _) :: _ => (true,   true)
          case OpInfo(_, nme.STAR, _, _) :: _                   => (false,  true)
          case _                                                => (false, false)
        }
        def isSeqPatternClose = isUnderscore && isStar && isSequenceOK && isDelimiter
        val preamble = "bad simple pattern:"
        val subtext = (isUnderscore, isStar, isSequenceOK) match {
          case (true,  true, true)  if isComma            => "bad use of _* (a sequence pattern must be the last pattern)"
          case (true,  true, true)  if isDelimiter        => "bad brace or paren after _*"
          case (true,  true, false) if isDelimiter        => "bad use of _* (sequence pattern not allowed)"
          case (false, true, true)  if isDelimiter        => "use _* to match a sequence"
          case (false, true, _)     if isCommaOrDelimiter => "trailing * is not a valid pattern"
          case _                                          => null
        }
        val msg = if (subtext != null) s"$preamble $subtext" else "illegal start of simple pattern"
        // better recovery if don't skip delims of patterns
        val skip = !isCommaOrDelimiter || isSeqPatternClose
        syntaxErrorOrIncompleteAnd(msg, skip)(errorPatternTree)
      }

      /** {{{
       *  SimplePattern    ::= varid
       *                    |  `_'
       *                    |  literal
       *                    |  XmlPattern
       *                    |  StableId  /[TypeArgs]/ [`(' [Patterns] `)']
       *                    |  StableId  [`(' [Patterns] `)']
       *                    |  StableId  [`(' [Patterns] `,' [varid `@'] `_' `*' `)']
       *                    |  `(' [Patterns] `)'
       *  }}}
       *
       * XXX: Hook for IDE
       */
      def simplePattern(): Tree = (
        // simple diagnostics for this entry point
        simplePattern(() => syntaxErrorOrIncompleteAnd("illegal start of simple pattern", skipIt = true)(errorPatternTree))
      )
      def simplePattern(onError: () => Tree): Tree = {
        val start = in.offset
        in.token match {
          case IDENTIFIER | BACKQUOTED_IDENT | THIS =>
            val t = stableId()
            in.token match {
              case INTLIT | LONGLIT | FLOATLIT | DOUBLELIT =>
                t match {
                  case Ident(nme.MINUS) =>
                    return literal(isNegated = true, inPattern = true, start = start)
                  case _ =>
                }
              case _ =>
            }
            val typeAppliedTree = in.token match {
              case LBRACKET   => AppliedTypeTree(convertToTypeId(t), typeArgs())
              case _          => t
            }
            in.token match {
              case LPAREN   => Apply(typeAppliedTree, argumentPatterns())
              case _        => typeAppliedTree
            }
          case USCORE =>
            in.nextToken()
            Ident(nme.WILDCARD)
          case CHARLIT | INTLIT | LONGLIT | FLOATLIT | DOUBLELIT |
               STRINGLIT | INTERPOLATIONID | SYMBOLLIT | TRUE | FALSE | NULL =>
            literal(inPattern = true)
          case LPAREN =>
            makeParens(noSeq.patterns())
          case XMLSTART =>
            xmlLiteralPattern()
          case _ =>
            onError()
        }
      }
    }
    /** The implementation of the context sensitive methods for parsing outside of patterns. */
    object outPattern extends PatternContextSensitive {
      def argType(): Tree = typ()
      def functionArgType(): Tree = paramType()
    }
    /** The implementation for parsing inside of patterns at points where sequences are allowed. */
    object seqOK extends SeqContextSensitive {
      val isSequenceOK = true
    }
    /** The implementation for parsing inside of patterns at points where sequences are disallowed. */
    object noSeq extends SeqContextSensitive {
      val isSequenceOK = false
    }
    /** For use from xml pattern, where sequence is allowed and encouraged. */
    object xmlSeqOK extends SeqContextSensitive {
      val isSequenceOK = true
      override val isXML = true
    }
    /** These are default entry points into the pattern context sensitive methods:
     *  they are all initiated from non-pattern context.
     */
    def typ(): Tree      = outPattern.typ()
    def startInfixType() = outPattern.infixType(InfixMode.FirstOp)
    def startAnnotType() = outPattern.annotType()
    def exprTypeArgs()   = outPattern.typeArgs()
    def exprSimpleType() = outPattern.simpleType()

    /** Default entry points into some pattern contexts. */
    def pattern(): Tree = noSeq.pattern()
    def seqPatterns(): List[Tree] = seqOK.patterns()
    def xmlSeqPatterns(): List[Tree] = xmlSeqOK.patterns() // Called from xml parser
    def argumentPatterns(): List[Tree] = inParens {
      if (in.token == RPAREN) Nil
      else seqPatterns()
    }
    def xmlLiteralPattern(): Tree

/* -------- MODIFIERS and ANNOTATIONS ------------------------------------------- */

    /** Drop `private` modifier when followed by a qualifier.
     *  Contract `abstract` and `override` to ABSOVERRIDE
     */
    private def normalizeModifers(mods: Modifiers): Modifiers =
      if (mods.isPrivate && mods.hasAccessBoundary)
        normalizeModifers(mods &~ Flags.PRIVATE)
      else if (mods hasAllFlags (Flags.ABSTRACT | Flags.OVERRIDE))
        normalizeModifers(mods &~ (Flags.ABSTRACT | Flags.OVERRIDE) | Flags.ABSOVERRIDE)
      else
        mods

    private def addMod(mods: Modifiers, mod: Long): Modifiers = {
      if (mods hasFlag mod) syntaxError(in.offset, "repeated modifier", skipIt = false)
      in.nextToken()
      (mods | mod)
    }

    /** {{{
     *  AccessQualifier ::= `[' (Id | this) `]'
     *  }}}
     */
    def accessQualifierOpt(mods: Modifiers): Modifiers = {
      var result = mods
      if (in.token == LBRACKET) {
        in.nextToken()
        if (mods.hasAccessBoundary)
          syntaxError("duplicate private/protected qualifier", skipIt = false)
        result = if (in.token == THIS) { in.nextToken(); mods | Flags.LOCAL }
                 else Modifiers(mods.flags, identForType())
        accept(RBRACKET)
      }
      result
    }

    private val flagTokens: Map[Int, Long] = Map(
      ABSTRACT  -> Flags.ABSTRACT,
      FINAL     -> Flags.FINAL,
      IMPLICIT  -> Flags.IMPLICIT,
      LAZY      -> Flags.LAZY,
      OVERRIDE  -> Flags.OVERRIDE,
      PRIVATE   -> Flags.PRIVATE,
      PROTECTED -> Flags.PROTECTED,
      SEALED    -> Flags.SEALED
    )

    /** {{{
     *  AccessModifier ::= (private | protected) [AccessQualifier]
     *  }}}
     */
    def accessModifierOpt(): Modifiers = normalizeModifers {
      in.token match {
        case m @ (PRIVATE | PROTECTED)  => in.nextToken() ; accessQualifierOpt(Modifiers(flagTokens(m)))
        case _                          => NoMods
      }
    }

    /** {{{
     *  Modifiers ::= {Modifier}
     *  Modifier  ::= LocalModifier
     *              |  AccessModifier
     *              |  override
     *  }}}
     */
    def modifiers(): Modifiers = normalizeModifers {
      def loop(mods: Modifiers): Modifiers = in.token match {
        case PRIVATE | PROTECTED =>
          loop(accessQualifierOpt(addMod(mods, flagTokens(in.token))))
        case ABSTRACT | FINAL | SEALED | OVERRIDE | IMPLICIT | LAZY =>
          loop(addMod(mods, flagTokens(in.token)))
        case NEWLINE =>
          in.nextToken()
          loop(mods)
        case _ =>
          mods
      }
      loop(NoMods)
    }

    /** {{{
     *  LocalModifiers ::= {LocalModifier}
     *  LocalModifier  ::= abstract | final | sealed | implicit | lazy
     *  }}}
     */
    def localModifiers(): Modifiers = {
      def loop(mods: Modifiers): Modifiers =
        if (isLocalModifier) loop(addMod(mods, flagTokens(in.token)))
        else mods

      loop(NoMods)
    }

    /** {{{
     *  Annotations      ::= {`@' SimpleType {ArgumentExprs}}
     *  ConsrAnnotations ::= {`@' SimpleType ArgumentExprs}
     *  }}}
     */
    def annotations(skipNewLines: Boolean): List[Tree] = readAnnots {
      val t = annotationExpr()
      if (skipNewLines) newLineOpt()
      t
    }
    def constructorAnnotations(): List[Tree] = readAnnots {
      New(exprSimpleType(), List(argumentExprs()))
    }

    def annotationExpr(): Tree = {
      val t = exprSimpleType()
      if (in.token == LPAREN) New(t, multipleArgumentExprs())
      else New(t, Nil)
    }

/* -------- PARAMETERS ------------------------------------------- */

    /** {{{
     *  ParamClauses      ::= {ParamClause} [[nl] `(' implicit Params `)']
     *  ParamClause       ::= [nl] `(' [Params] `)'
     *  Params            ::= Param {`,' Param}
     *  Param             ::= {Annotation} Id [`:' ParamType] [`=' Expr]
     *  ClassParamClauses ::= {ClassParamClause} [[nl] `(' implicit ClassParams `)']
     *  ClassParamClause  ::= [nl] `(' [ClassParams] `)'
     *  ClassParams       ::= ClassParam {`,' ClassParam}
     *  ClassParam        ::= {Annotation}  [{Modifier} (`val' | `var')] Id [`:' ParamType] [`=' Expr]
     *  }}}
     */
    def paramClauses(owner: Name, contextBounds: List[Tree], ofCaseClass: Boolean): List[List[ValDef]] = {
      var implicitmod = 0
      var caseParam = ofCaseClass
      def paramClause(): List[ValDef] = {
        if (in.token == RPAREN)
          return Nil

        if (in.token == IMPLICIT) {
          in.nextToken()
          implicitmod = Flags.IMPLICIT
        }
        commaSeparated(param(owner, implicitmod, caseParam  ))
      }
      val vds = new ListBuffer[List[ValDef]]
      val start = in.offset
      newLineOptWhenFollowedBy(LPAREN)
      if (ofCaseClass && in.token != LPAREN)
        syntaxError(in.lastOffset, "case classes without a parameter list are not allowed;\n"+
                                   "use either case objects or case classes with an explicit `()' as a parameter list.")
      while (implicitmod == 0 && in.token == LPAREN) {
        in.nextToken()
        vds += paramClause()
        accept(RPAREN)
        caseParam = false
        newLineOptWhenFollowedBy(LPAREN)
      }
      val result = vds.toList
      if (owner == nme.CONSTRUCTOR && (result.isEmpty || (result.head take 1 exists (_.mods.isImplicit)))) {
        in.token match {
          case LBRACKET   => syntaxError(in.offset, "no type parameters allowed here", skipIt = false)
          case EOF        => incompleteInputError("auxiliary constructor needs non-implicit parameter list")
          case _          => syntaxError(start, "auxiliary constructor needs non-implicit parameter list", skipIt = false)
        }
      }
      addEvidenceParams(owner, result, contextBounds)
    }

    /** {{{
     *  ParamType ::= Type | `=>' Type | Type `*'
     *  }}}
     */
    def paramType(): Tree = {
      val start = in.offset
      in.token match {
        case ARROW  =>
          in.nextToken()
          byNameApplication(typ())
        case _      =>
          val t = typ()
          if (isRawStar) {
            in.nextToken()
            repeatedApplication(t)
          }
          else t
      }
    }

    def param(owner: Name, implicitmod: Int, caseParam: Boolean): ValDef = {
      val start = in.offset
      val annots = annotations(skipNewLines = false)
      var mods = Modifiers(Flags.PARAM)
      if (owner.isTypeName) {
        mods = modifiers() | Flags.PARAMACCESSOR
        if (mods.isLazy) syntaxError("lazy modifier not allowed here. Use call-by-name parameters instead", skipIt = false)
        in.token match {
          case v @ (VAL | VAR) =>
            if (v == VAR) mods |= Flags.MUTABLE
            in.nextToken()
          case _ =>
            if (mods.flags != Flags.PARAMACCESSOR) accept(VAL)
            if (!caseParam) mods |= Flags.PrivateLocal
        }
        if (caseParam) mods |= Flags.CASEACCESSOR
      }
      val nameOffset = in.offset
      val name = ident()
      var bynamemod = 0
      val tpt =
        if ((settings.YmethodInfer && !owner.isTypeName) && in.token != COLON) {
          TypeTree()
        } else { // XX-METHOD-INFER
          accept(COLON)
          if (in.token == ARROW) {
            if (owner.isTypeName && !mods.isLocalToThis)
              syntaxError(
                in.offset,
                (if (mods.isMutable) "`var'" else "`val'") +
                " parameters may not be call-by-name", skipIt = false)
            else if (implicitmod != 0)
              syntaxError(
                in.offset,
                "implicit parameters may not be call-by-name", skipIt = false)
            else bynamemod = Flags.BYNAMEPARAM
          }
          paramType()
        }
      val default =
        if (in.token == EQUALS) {
          in.nextToken()
          mods |= Flags.DEFAULTPARAM
          expr()
        } else EmptyTree
      ValDef((mods | implicitmod.toLong | bynamemod) withAnnotations annots, name.toTermName, tpt, default)
    }

    /** {{{
     *  TypeParamClauseOpt    ::= [TypeParamClause]
     *  TypeParamClause       ::= `[' VariantTypeParam {`,' VariantTypeParam} `]']
     *  VariantTypeParam      ::= {Annotation} [`+' | `-'] TypeParam
     *  FunTypeParamClauseOpt ::= [FunTypeParamClause]
     *  FunTypeParamClause    ::= `[' TypeParam {`,' TypeParam} `]']
     *  TypeParam             ::= Id TypeParamClauseOpt TypeBounds {<% Type} {":" Type}
     *  }}}
     */
    def typeParamClauseOpt(owner: Name, contextBoundBuf: ListBuffer[Tree]): List[TypeDef] = {
      def typeParam(ms: Modifiers): TypeDef = {
        var mods = ms | Flags.PARAM
        val start = in.offset
        if (owner.isTypeName && isIdent) {
          if (in.name == raw.PLUS) {
            in.nextToken()
            mods |= Flags.COVARIANT
          } else if (in.name == raw.MINUS) {
            in.nextToken()
            mods |= Flags.CONTRAVARIANT
          }
        }
        val nameOffset = in.offset
        val pname: TypeName = wildcardOrIdent().toTypeName
        val param = {
          val tparams = typeParamClauseOpt(pname, null) // @M TODO null --> no higher-order context bounds for now
          TypeDef(mods, pname, tparams, typeBounds())
        }
        if (contextBoundBuf ne null) {
          while (in.token == VIEWBOUND) {
            val msg = "Use an implicit parameter instead.\nExample: Instead of `def f[A <% Int](a: A)` use `def f[A](a: A)(implicit ev: A => Int)`."
            if (settings.future)
              deprecationWarning(in.offset, s"View bounds are deprecated. $msg")
            in.skipToken()
            contextBoundBuf += makeFunctionTypeTree(List(Ident(pname)), typ())
          }
          while (in.token == COLON) {
            in.skipToken()
            contextBoundBuf += {
              AppliedTypeTree(typ(), List(Ident(pname)))
            }
          }
        }
        param
      }
      newLineOptWhenFollowedBy(LBRACKET)
      if (in.token == LBRACKET) inBrackets(commaSeparated(typeParam(NoMods withAnnotations annotations(skipNewLines = true))))
      else Nil
    }

    /** {{{
     *  TypeBounds ::= [`>:' Type] [`<:' Type]
     *  }}}
     */
    def typeBounds(): TypeBoundsTree = {
      val lo      = bound(SUPERTYPE)
      val hi      = bound(SUBTYPE)
      val t       = TypeBoundsTree(lo, hi)
      t
    }

    def bound(tok: Token): Tree = if (in.token == tok) { in.nextToken(); typ() } else EmptyTree

/* -------- DEFS ------------------------------------------- */


    /** {{{
     *  Import  ::= import ImportExpr {`,' ImportExpr}
     *  }}}
     */
    def importClause(): List[Tree] = {
      val offset = accept(IMPORT)
      commaSeparated(importExpr())
    }

    /** {{{
     *  ImportExpr ::= StableId `.' (Id | `_' | ImportSelectors)
     *  }}}
     */
    def importExpr(): Tree = {
      val start = in.offset
      def thisDotted(name: TypeName) = {
        in.nextToken()
        val t = This(name)
        accept(DOT)
        val result = selector(t)
        accept(DOT)
        result
      }
      /* Walks down import `foo.bar.baz.{ ... }` until it ends at a
       * an underscore, a left brace, or an undotted identifier.
       */
      def loop(expr: Tree): Tree = {
        val selectors: List[ImportSelector] = in.token match {
          case USCORE   => List(importSelector()) // import foo.bar._;
          case LBRACE   => importSelectors()      // import foo.bar.{ x, y, z }
          case _        =>
            val nameOffset = in.offset
            val name = ident()
            if (in.token == DOT) {
              // import foo.bar.ident.<unknown> and so create a select node and recurse.
              val t = Select(expr, name)
              in.nextToken()
              return loop(t)
            }
            // import foo.bar.Baz;
            else List(makeImportSelector(name, nameOffset))
        }
        // reaching here means we're done walking.
        Import(expr, selectors)
      }

      loop(in.token match {
        case THIS   => thisDotted(tpnme.EMPTY)
        case _      =>
          val id = Ident(ident())
          accept(DOT)
          if (in.token == THIS) thisDotted(id.name.toTypeName)
          else id
      })
    }

    /** {{{
     *  ImportSelectors ::= `{' {ImportSelector `,'} (ImportSelector | `_') `}'
     *  }}}
     */
    def importSelectors(): List[ImportSelector] = {
      val selectors = inBracesOrNil(commaSeparated(importSelector()))
      selectors.init foreach {
        case ImportSelector(nme.WILDCARD, pos, _, _)  => syntaxError(pos, "Wildcard import must be in last position")
        case _                                        => ()
      }
      selectors
    }

    def wildcardOrIdent() = {
      if (in.token == USCORE) { in.nextToken() ; nme.WILDCARD }
      else ident()
    }

    /** {{{
     *  ImportSelector ::= Id [`=>' Id | `=>' `_']
     *  }}}
     */
    def importSelector(): ImportSelector = {
      val start        = in.offset
      val name         = wildcardOrIdent()
      var renameOffset = -1
      val rename       = in.token match {
        case ARROW    =>
          in.nextToken()
          renameOffset = in.offset
          wildcardOrIdent()
        case _ if name == nme.WILDCARD  => null
        case _ =>
          renameOffset = start
          name
      }
      ImportSelector(name, start, rename, renameOffset)
    }

    /** {{{
     *  Def    ::= val PatDef
     *           | var PatDef
     *           | def FunDef
     *           | type [nl] TypeDef
     *           | TmplDef
     *  Dcl    ::= val PatDcl
     *           | var PatDcl
     *           | def FunDcl
     *           | type [nl] TypeDcl
     *  }}}
     */
    def defOrDcl(pos: Offset, mods: Modifiers): List[Tree] = {
      if (mods.isLazy && in.token != VAL)
        syntaxError("lazy not allowed here. Only vals can be lazy", skipIt = false)
      in.token match {
        case VAL =>
          patDefOrDcl(mods)
        case VAR =>
          patDefOrDcl(mods | Flags.MUTABLE)
        case DEF =>
          List(funDefOrDcl(mods))
        case TYPE =>
          List(typeDefOrDcl(mods))
        case _ =>
          List(tmplDef(pos, mods))
      }
    }

    private def caseAwareTokenOffset = if (in.token == CASECLASS || in.token == CASEOBJECT) in.prev.offset else in.offset

    def nonLocalDefOrDcl : List[Tree] = {
      val annots = annotations(skipNewLines = true)
      defOrDcl(caseAwareTokenOffset, modifiers() withAnnotations annots)
    }

    /** {{{
     *  PatDef ::= Pattern2 {`,' Pattern2} [`:' Type] `=' Expr
     *  ValDcl ::= Id {`,' Id} `:' Type
     *  VarDef ::= PatDef | Id {`,' Id} `:' Type `=' `_'
     *  }}}
     */
    def patDefOrDcl(mods: Modifiers): List[Tree] = {
      var newmods = mods
      in.nextToken()
      val lhs = commaSeparated(stripParens(noSeq.pattern2()))
      val tp = typedOpt()
      val rhs =
        if (tp.isEmpty || in.token == EQUALS) {
          accept(EQUALS)
          if (!tp.isEmpty && newmods.isMutable &&
              (lhs.toList forall (_.isInstanceOf[Ident])) && in.token == USCORE) {
            in.nextToken()
            newmods = newmods | Flags.DEFAULTINIT
            EmptyTree
          } else {
            expr()
          }
        } else {
          newmods = newmods | Flags.DEFERRED
          EmptyTree
        }
      def mkDefs(p: Tree, tp: Tree, rhs: Tree): List[Tree] = {
        val trees = {
          val pat = if (tp.isEmpty) p else Typed(p, tp)
          makePatDef(newmods, pat, rhs)
        }
        if (newmods.isDeferred) {
          trees match {
            case List(ValDef(_, _, _, EmptyTree)) =>
              if (mods.isLazy) syntaxError(/* p.pos */ ???, "lazy values may not be abstract", skipIt = false)
            case _ => syntaxError(/* p.pos */ ???, "pattern definition may not be abstract", skipIt = false)
          }
        }
        trees
      }
      val trees = (lhs.toList.init flatMap (mkDefs(_, tp.duplicate, rhs.duplicate))) ::: mkDefs(lhs.last, tp, rhs)
      val hd = trees.head
      trees
    }

    /** {{{
     *  VarDef ::= PatDef
     *           | Id {`,' Id} `:' Type `=' `_'
     *  VarDcl ::= Id {`,' Id} `:' Type
     *  }}}
    def varDefOrDcl(mods: Modifiers): List[Tree] = {
      var newmods = mods | Flags.MUTABLE
      val lhs = new ListBuffer[(Int, Name)]
      do {
        in.nextToken()
        lhs += (in.offset, ident())
      } while (in.token == COMMA)
      val tp = typedOpt()
      val rhs = if (tp.isEmpty || in.token == EQUALS) {
        accept(EQUALS)
        if (!tp.isEmpty && in.token == USCORE) {
          in.nextToken()
          EmptyTree
        } else {
          expr()
        }
      } else {
        newmods = newmods | Flags.DEFERRED
        EmptyTree
      }
    }
     */

    /** {{{
     *  FunDef ::= FunSig [`:' Type] `=' [`macro'] Expr
     *          |  FunSig [nl] `{' Block `}'
     *          |  `this' ParamClause ParamClauses
     *                 (`=' ConstrExpr | [nl] ConstrBlock)
     *  FunDcl ::= FunSig [`:' Type]
     *  FunSig ::= id [FunTypeParamClause] ParamClauses
     *  }}}
     */
    def funDefOrDcl(mods: Modifiers): Tree = {
      in.nextToken()
      if (in.token == THIS) {
        in.skipToken()
        val vparamss = paramClauses(nme.CONSTRUCTOR, classContextBounds map (_.duplicate), ofCaseClass = false)
        newLineOptWhenFollowedBy(LBRACE)
        val rhs = in.token match {
          case LBRACE   => constrBlock(vparamss)
          case _        => accept(EQUALS); constrExpr(vparamss)
        }
        DefDef(mods, nme.CONSTRUCTOR, List(), vparamss, TypeTree(), rhs)
      }
      else {
        val nameOffset = in.offset
        val name = identOrMacro()
        funDefRest(mods, name)
      }
    }

    def funDefRest(mods: Modifiers, name: Name): Tree = {
      val result = {
        var newmods = mods
        // contextBoundBuf is for context bounded type parameters of the form
        // [T : B] or [T : => B]; it contains the equivalent implicit parameter type,
        // i.e. (B[T] or T => B)
        val contextBoundBuf = new ListBuffer[Tree]
        val tparams = typeParamClauseOpt(name, contextBoundBuf)
        val vparamss = paramClauses(name, contextBoundBuf.toList, ofCaseClass = false)
        newLineOptWhenFollowedBy(LBRACE)
        var restype = fromWithinReturnType(typedOpt())
        val rhs =
          if (isStatSep || in.token == RBRACE) {
            if (restype.isEmpty) {
              if (settings.future)
                deprecationWarning(in.lastOffset, s"Procedure syntax is deprecated. Convert procedure `$name` to method by adding `: Unit`.")
              restype = scalaUnitConstr
            }
            newmods |= Flags.DEFERRED
            EmptyTree
          } else if (restype.isEmpty && in.token == LBRACE) {
            if (settings.future)
              deprecationWarning(in.offset, s"Procedure syntax is deprecated. Convert procedure `$name` to method by adding `: Unit =`.")
            restype = scalaUnitConstr
            blockExpr()
          } else {
            if (in.token == EQUALS) {
              in.nextTokenAllow(nme.MACROkw)
              if (isMacro) {
                in.nextToken()
                newmods |= Flags.MACRO
              }
            } else {
              accept(EQUALS)
            }
            expr()
          }
        DefDef(newmods, name.toTermName, tparams, vparamss, restype, rhs)
      }
      result
    }

    /** {{{
     *  ConstrExpr      ::=  SelfInvocation
     *                    |  ConstrBlock
     *  }}}
     */
    def constrExpr(vparamss: List[List[ValDef]]): Tree =
      if (in.token == LBRACE) constrBlock(vparamss)
      else Block(selfInvocation(vparamss) :: Nil, literalUnit)

    /** {{{
     *  SelfInvocation  ::= this ArgumentExprs {ArgumentExprs}
     *  }}}
     */
    def selfInvocation(vparamss: List[List[ValDef]]): Tree = {
      accept(THIS)
      newLineOptWhenFollowedBy(LBRACE)
      var t = Apply(Ident(nme.CONSTRUCTOR), argumentExprs())
      newLineOptWhenFollowedBy(LBRACE)
      while (in.token == LPAREN || in.token == LBRACE) {
        t = Apply(t, argumentExprs())
        newLineOptWhenFollowedBy(LBRACE)
      }
      if (classContextBounds.isEmpty) t
      else Apply(t, vparamss.last.map(vp => Ident(vp.name)))
    }

    /** {{{
     *  ConstrBlock    ::=  `{' SelfInvocation {semi BlockStat} `}'
     *  }}}
     */
    def constrBlock(vparamss: List[List[ValDef]]): Tree = {
      in.skipToken()
      val stats = selfInvocation(vparamss) :: {
        if (isStatSep) { in.nextToken(); blockStatSeq() }
        else Nil
      }
      accept(RBRACE)
      Block(stats, literalUnit)
    }

    /** {{{
     *  TypeDef ::= type Id [TypeParamClause] `=' Type
     *            | FunSig `=' Expr
     *  TypeDcl ::= type Id [TypeParamClause] TypeBounds
     *  }}}
     */
    def typeDefOrDcl(mods: Modifiers): Tree = {
      in.nextToken()
      newLinesOpt()
      val name = identForType()
      // @M! a type alias as well as an abstract type may declare type parameters
      val tparams = typeParamClauseOpt(name, null)
      in.token match {
        case EQUALS =>
          in.nextToken()
          TypeDef(mods, name, tparams, typ())
        case t if t == SUPERTYPE || t == SUBTYPE || t == COMMA || t == RBRACE || isStatSep(t) =>
          TypeDef(mods | Flags.DEFERRED, name, tparams, typeBounds())
        case _ =>
          syntaxErrorOrIncompleteAnd("`=', `>:', or `<:' expected", skipIt = true)(EmptyTree)
      }
    }

    /** Hook for IDE, for top-level classes/objects. */
    def topLevelTmplDef: Tree = {
      val annots = annotations(skipNewLines = true)
      val pos    = caseAwareTokenOffset
      val mods   = modifiers() withAnnotations annots
      tmplDef(pos, mods)
    }

    /** {{{
     *  TmplDef ::= [case] class ClassDef
     *            |  [case] object ObjectDef
     *            |  [override] trait TraitDef
     *  }}}
     */
    def tmplDef(pos: Offset, mods: Modifiers): Tree = {
      if (mods.isLazy) syntaxError("classes cannot be lazy", skipIt = false)
      in.token match {
        case TRAIT =>
          classDef(pos, (mods | Flags.TRAIT | Flags.ABSTRACT))
        case CLASS =>
          classDef(pos, mods)
        case CASECLASS =>
          classDef(pos, (mods | Flags.CASE))
        case OBJECT =>
          objectDef(pos, mods)
        case CASEOBJECT =>
          objectDef(pos, (mods | Flags.CASE))
        case _ =>
          syntaxErrorOrIncompleteAnd("expected start of definition", skipIt = true)(EmptyTree)
      }
    }

    /** {{{
     *  ClassDef ::= Id [TypeParamClause] {Annotation}
     *               [AccessModifier] ClassParamClauses RequiresTypeOpt ClassTemplateOpt
     *  TraitDef ::= Id [TypeParamClause] RequiresTypeOpt TraitTemplateOpt
     *  }}}
     */
    def classDef(start: Offset, mods: Modifiers): ClassDef = {
      in.nextToken()
      val nameOffset = in.offset
      val name = identForType()
      savingClassContextBounds {
        val contextBoundBuf = new ListBuffer[Tree]
        val tparams = typeParamClauseOpt(name, contextBoundBuf)
        classContextBounds = contextBoundBuf.toList
        val tstart = in.offset
        if (!classContextBounds.isEmpty && mods.isTrait) {
          val viewBoundsExist = if (settings.future) "" else " nor view bounds `<% ...'"
            syntaxError(s"traits cannot have type parameters with context bounds `: ...'$viewBoundsExist", skipIt = false)
          classContextBounds = List()
        }
        val constrAnnots = if (!mods.isTrait) constructorAnnotations() else Nil
        val (constrMods, vparamss) =
          if (mods.isTrait) (Modifiers(Flags.TRAIT), List())
          else (accessModifierOpt(), paramClauses(name, classContextBounds, ofCaseClass = mods.isCase))
        var mods1 = mods
        if (mods.isTrait) {
          if (settings.YvirtClasses && in.token == SUBTYPE) mods1 |= Flags.DEFERRED
        } else if (in.token == SUBTYPE) {
          syntaxError("classes are not allowed to be virtual", skipIt = false)
        }
        val template = templateOpt(mods1, name, constrMods withAnnotations constrAnnots, vparamss, tstart)
        val result = TreeGen.mkClassDef(mods1, name, tparams, template)
        result
      }
    }

    /** {{{
     *  ObjectDef       ::= Id ClassTemplateOpt
     *  }}}
     */
    def objectDef(start: Offset, mods: Modifiers): ModuleDef = {
      in.nextToken()
      val nameOffset = in.offset
      val name = ident()
      val tstart = in.offset
      val mods1 = if (in.token == SUBTYPE) mods | Flags.DEFERRED else mods
      val template = templateOpt(mods1, name, NoMods, Nil, tstart)
      ModuleDef(mods1, name.toTermName, template)
    }

    /** Create a tree representing a package object, converting
     *  {{{
     *    package object foo { ... }
     *  }}}
     *  to
     *  {{{
     *    package foo {
     *      object `package` { ... }
     *    }
     *  }}}
     */
    def packageObjectDef(start: Offset): PackageDef = {
      val defn   = objectDef(in.offset, NoMods)
      TreeGen.mkPackageObject(defn)
    }
    def packageOrPackageObject(start: Offset): Tree = (
      if (in.token == OBJECT)
        joinComment(packageObjectDef(start) :: Nil).head
      else {
        in.flushDoc
        makePackaging(pkgQualId(), inBracesOrNil(topStatSeq()))
      }
    )
    // TODO - eliminate this and use "def packageObjectDef" (see call site of this
    // method for small elaboration.)
    def makePackageObject(start: Offset, objDef: ModuleDef): PackageDef = objDef match {
      case ModuleDef(mods, name, impl) =>
        makePackaging(Ident(name), List(ModuleDef(mods, nme.PACKAGEkw, impl)))
    }

    /** {{{
     *  ClassParents       ::= AnnotType {`(' [Exprs] `)'} {with AnnotType}
     *  TraitParents       ::= AnnotType {with AnnotType}
     *  }}}
     */
    def templateParents(): List[Tree] = {
      val parents = new ListBuffer[Tree]
      def readAppliedParent() = {
        val start = in.offset
        val parent = startAnnotType()
        parents += (in.token match {
          case LPAREN => (parent /: multipleArgumentExprs())(Apply.apply)
          case _      => parent
        })
      }
      readAppliedParent()
      while (in.token == WITH) { in.nextToken(); readAppliedParent() }
      parents.toList
    }

    /** {{{
     *  ClassTemplate ::= [EarlyDefs with] ClassParents [TemplateBody]
     *  TraitTemplate ::= [EarlyDefs with] TraitParents [TemplateBody]
     *  EarlyDefs     ::= `{' [EarlyDef {semi EarlyDef}] `}'
     *  EarlyDef      ::= Annotations Modifiers PatDef
     *  }}}
     */
    def template(): (List[Tree], ValDef, List[Tree]) = {
      newLineOptWhenFollowedBy(LBRACE)
      if (in.token == LBRACE) {
        // @S: pre template body cannot stub like post body can!
        val (self, body) = templateBody(isPre = true)
        if (in.token == WITH && (self eq noSelfType)) {
          val earlyDefs: List[Tree] = body.map(ensureEarlyDef).filter(_.nonEmpty)
          in.nextToken()
          val parents = templateParents()
          val (self1, body1) = templateBodyOpt(parenMeansSyntaxError = false)
          (parents, self1, earlyDefs ::: body1)
        } else {
          (List(), self, body)
        }
      } else {
        val parents = templateParents()
        val (self, body) = templateBodyOpt(parenMeansSyntaxError = false)
        (parents, self, body)
      }
    }

    def ensureEarlyDef(tree: Tree): Tree = tree match {
      case vdef @ ValDef(mods, _, _, _) if !mods.isDeferred =>
        copyValDef(vdef)(mods = mods | Flags.PRESUPER)
      case tdef @ TypeDef(mods, name, tparams, rhs) =>
        deprecationWarning(/* tdef.pos.point */ ???, "early type members are deprecated. Move them to the regular body: the semantics are the same.")
        treeCopy.TypeDef(tdef, mods | Flags.PRESUPER, name, tparams, rhs)
      case docdef @ DocDef(comm, rhs) =>
        treeCopy.DocDef(docdef, comm, rhs)
      case stat if !stat.isEmpty =>
        syntaxError(/* stat.pos */ ???, "only concrete field definitions allowed in early object initialization section", skipIt = false)
        EmptyTree
      case _ =>
        EmptyTree
    }

    /** {{{
     *  ClassTemplateOpt ::= `extends' ClassTemplate | [[`extends'] TemplateBody]
     *  TraitTemplateOpt ::= TraitExtends TraitTemplate | [[`extends'] TemplateBody] | `<:' TemplateBody
     *  TraitExtends     ::= `extends' | `<:'
     *  }}}
     */
    def templateOpt(mods: Modifiers, name: Name, constrMods: Modifiers, vparamss: List[List[ValDef]], tstart: Offset): Template = {
      val (parents, self, body) = (
        if (in.token == EXTENDS || in.token == SUBTYPE && mods.isTrait) {
          in.nextToken()
          template()
        }
        else {
          newLineOptWhenFollowedBy(LBRACE)
          val (self, body) = templateBodyOpt(parenMeansSyntaxError = mods.isTrait || name.isTermName)
          (List(), self, body)
        }
      )
      def anyvalConstructor() = (
        // Not a well-formed constructor, has to be finished later - see note
        // regarding AnyVal constructor in AddInterfaces.
        DefDef(NoMods, nme.CONSTRUCTOR, Nil, List(Nil), TypeTree(), Block(Nil, literalUnit))
      )
      val tstart1 = if (body.isEmpty && in.lastOffset < tstart) in.lastOffset else tstart

      {
        // Exclude only the 9 primitives plus AnyVal.
        if (inScalaRootPackage && ScalaValueClassNames.contains(name))
          Template(parents, self, anyvalConstructor :: body)
        else
          TreeGen.mkTemplate(TreeGen.mkParents(mods, parents),
                             self, constrMods, vparamss, body)
      }
    }

/* -------- TEMPLATES ------------------------------------------- */

    /** {{{
     *  TemplateBody ::= [nl] `{' TemplateStatSeq `}'
     *  }}}
     * @param isPre specifies whether in early initializer (true) or not (false)
     */
    def templateBody(isPre: Boolean) = inBraces(templateStatSeq(isPre = isPre)) match {
      case (self, Nil)  => (self, EmptyTree.asList)
      case result       => result
    }
    def templateBodyOpt(parenMeansSyntaxError: Boolean): (ValDef, List[Tree]) = {
      newLineOptWhenFollowedBy(LBRACE)
      if (in.token == LBRACE) {
        templateBody(isPre = false)
      } else {
        if (in.token == LPAREN) {
          if (parenMeansSyntaxError) syntaxError(s"traits or objects may not have parameters", skipIt = true)
          else abort("unexpected opening parenthesis")
        }
        (noSelfType, List())
      }
    }

    /** {{{
     *  Refinement ::= [nl] `{' RefineStat {semi RefineStat} `}'
     *  }}}
     */
    def refinement(): List[Tree] = inBraces(refineStatSeq())

/* -------- STATSEQS ------------------------------------------- */

  /** Create a tree representing a packaging. */
    def makePackaging(pkg: Tree, stats: List[Tree]): PackageDef = pkg match {
      case x: RefTree => PackageDef(x, stats)
    }

    def makeEmptyPackage(stats: List[Tree]): PackageDef = (
      makePackaging(Ident(nme.EMPTY_PACKAGE_NAME), stats)
    )

    def statSeq(stat: PartialFunction[Token, List[Tree]], errorMsg: String = "illegal start of definition"): List[Tree] = {
      val stats = new ListBuffer[Tree]
      def default(tok: Token) =
        if (isStatSep) Nil
        else syntaxErrorOrIncompleteAnd(errorMsg, skipIt = true)(Nil)
      while (!isStatSeqEnd) {
        stats ++= stat.applyOrElse(in.token, default)
        acceptStatSepOpt()
      }
      stats.toList
    }

    /** {{{
     *  TopStatSeq ::= TopStat {semi TopStat}
     *  TopStat ::= Annotations Modifiers TmplDef
     *            | Packaging
     *            | package object objectDef
     *            | Import
     *            |
     *  }}}
     */
    def topStatSeq(): List[Tree] = statSeq(topStat, errorMsg = "expected class or object definition")
    def topStat: PartialFunction[Token, List[Tree]] = {
      case PACKAGE  =>
        packageOrPackageObject(in.skipToken()) :: Nil
      case IMPORT =>
        in.flushDoc
        importClause()
      case _ if isAnnotation || isTemplateIntro || isModifier =>
        joinComment(topLevelTmplDef :: Nil)
    }

    /** {{{
     *  TemplateStatSeq  ::= [id [`:' Type] `=>'] TemplateStats
     *  }}}
     * @param isPre specifies whether in early initializer (true) or not (false)
     */
    def templateStatSeq(isPre : Boolean): (ValDef, List[Tree]) = checkNoEscapingPlaceholders {
      var self: ValDef = noSelfType
      var firstOpt: Option[Tree] = None
      if (isExprIntro) {
        in.flushDoc
        val first = expr(InTemplate) // @S: first statement is potentially converted so cannot be stubbed.
        if (in.token == ARROW) {
          first match {
            case Typed(tree @ This(tpnme.EMPTY), tpt) =>
              self =  makeSelfDef(nme.WILDCARD, tpt)
            case _ =>
              convertToParam(first) match {
                case tree @ ValDef(_, name, tpt, EmptyTree) if (name != nme.ERROR) =>
                  self = makeSelfDef(name, tpt)
                case _ =>
              }
          }
          in.nextToken()
        } else {
          firstOpt = Some(first)
          acceptStatSepOpt()
        }
      }
      (self, firstOpt ++: templateStats())
    }

    /** {{{
     *  TemplateStats    ::= TemplateStat {semi TemplateStat}
     *  TemplateStat     ::= Import
     *                     | Annotations Modifiers Def
     *                     | Annotations Modifiers Dcl
     *                     | Expr1
     *                     | super ArgumentExprs {ArgumentExprs}
     *                     |
     *  }}}
     */
    def templateStats(): List[Tree] = statSeq(templateStat)
    def templateStat: PartialFunction[Token, List[Tree]] = {
      case IMPORT =>
        in.flushDoc
        importClause()
      case _ if isDefIntro || isModifier || isAnnotation =>
        joinComment(nonLocalDefOrDcl)
      case _ if isExprIntro =>
        in.flushDoc
        statement(InTemplate) :: Nil
    }

    def templateOrTopStatSeq(): List[Tree] = statSeq(templateStat.orElse(topStat))

    /** {{{
     *  RefineStatSeq    ::= RefineStat {semi RefineStat}
     *  RefineStat       ::= Dcl
     *                     | type TypeDef
     *                     |
     *  }}}
     */
    def refineStatSeq(): List[Tree] = checkNoEscapingPlaceholders {
      val stats = new ListBuffer[Tree]
      while (!isStatSeqEnd) {
        stats ++= refineStat()
        if (in.token != RBRACE) acceptStatSep()
      }
      stats.toList
    }

    def refineStat(): List[Tree] =
      if (isDclIntro) { // don't IDE hook
        joinComment(defOrDcl(in.offset, NoMods))
      } else if (!isStatSep) {
        syntaxErrorOrIncomplete(
          "illegal start of declaration"+
          (if (inFunReturnType) " (possible cause: missing `=' in front of current method body)"
           else ""), skipIt = true)
        Nil
      } else Nil

    /** overridable IDE hook for local definitions of blockStatSeq
     *  Here's an idea how to fill in start and end positions.
    def localDef : List[Tree] = {
      atEndPos {
        atStartPos(in.offset) {
          val annots = annotations(skipNewLines = true)
          val mods = localModifiers() withAnnotations annots
          if (!(mods hasFlag ~(Flags.IMPLICIT | Flags.LAZY))) defOrDcl(mods)
          else List(tmplDef(mods))
        }
      } (in.offset)
    }
    */

    def localDef(implicitMod: Int): List[Tree] = {
      val annots = annotations(skipNewLines = true)
      val pos = in.offset
      val mods = (localModifiers() | implicitMod.toLong) withAnnotations annots
      val defs =
        if (!(mods hasFlag ~(Flags.IMPLICIT | Flags.LAZY))) defOrDcl(pos, mods)
        else List(tmplDef(pos, mods))

      in.token match {
        case RBRACE | CASE  => defs :+ literalUnit
        case _              => defs
      }
    }

    /** {{{
     *  BlockStatSeq ::= { BlockStat semi } [ResultExpr]
     *  BlockStat    ::= Import
     *                 | Annotations [implicit] [lazy] Def
     *                 | Annotations LocalModifiers TmplDef
     *                 | Expr1
     *                 |
     *  }}}
     */
    def blockStatSeq(): List[Tree] = checkNoEscapingPlaceholders {
      val stats = new ListBuffer[Tree]
      while (!isStatSeqEnd && !isCaseDefEnd) {
        if (in.token == IMPORT) {
          stats ++= importClause()
          acceptStatSepOpt()
        }
        else if (isDefIntro || isLocalModifier || isAnnotation) {
          if (in.token == IMPLICIT) {
            val start = in.skipToken()
            if (isIdent) stats += implicitClosure(start, InBlock)
            else stats ++= localDef(Flags.IMPLICIT)
          } else {
            stats ++= localDef(0)
          }
          acceptStatSepOpt()
        }
        else if (isExprIntro) {
          stats += statement(InBlock)
          if (!isCaseDefEnd) acceptStatSep()
        }
        else if (isStatSep) {
          in.nextToken()
        }
        else {
          val addendum = if (isModifier) " (no modifiers allowed here)" else ""
          syntaxErrorOrIncomplete("illegal start of statement" + addendum, skipIt = true)
        }
      }
      stats.toList
    }

    /** {{{
     *  CompilationUnit ::= {package QualId semi} TopStatSeq
     *  }}}
     */
    def compilationUnit(): PackageDef = checkNoEscapingPlaceholders {
      def topstats(): List[Tree] = {
        val ts = new ListBuffer[Tree]
        while (in.token == SEMI) in.nextToken()
        val start = in.offset
        if (in.token == PACKAGE) {
          in.nextToken()
          if (in.token == OBJECT) {
            // TODO - this next line is supposed to be
            //    ts += packageObjectDef(start)
            // but this broke a scaladoc test (run/diagrams-filtering.scala) somehow.
            ts ++= joinComment(List(makePackageObject(start, objectDef(in.offset, NoMods))))
            if (in.token != EOF) {
              acceptStatSep()
              ts ++= topStatSeq()
            }
          } else {
            in.flushDoc
            val pkg = pkgQualId()

            if (in.token == EOF) {
              ts += makePackaging(pkg, List())
            } else if (isStatSep) {
              in.nextToken()
              ts += makePackaging(pkg, topstats())
            } else {
              ts += inBraces(makePackaging(pkg, topStatSeq()))
              acceptStatSepOpt()
              ts ++= topStatSeq()
            }
          }
        } else {
          ts ++= topStatSeq()
        }
        ts.toList
      }

      resetPackage()
      topstats() match {
        case (stat @ PackageDef(_, _)) :: Nil => stat
        case stats                            =>
          makeEmptyPackage(stats)
      }
    }
  }
}

object Parsers extends Parsers
