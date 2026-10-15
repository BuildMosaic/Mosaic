package org.buildmosaic.compiler

import org.buildmosaic.analysis.SourceLocation
import org.jetbrains.kotlin.ir.IrElement
import org.jetbrains.kotlin.ir.declarations.IrConstructor
import org.jetbrains.kotlin.ir.declarations.IrFile
import org.jetbrains.kotlin.ir.declarations.IrLocalDelegatedProperty
import org.jetbrains.kotlin.ir.declarations.IrProperty
import org.jetbrains.kotlin.ir.declarations.IrSimpleFunction
import org.jetbrains.kotlin.ir.declarations.IrVariable
import org.jetbrains.kotlin.ir.expressions.IrDelegatingConstructorCall
import org.jetbrains.kotlin.lexer.KotlinLexer
import org.jetbrains.kotlin.lexer.KtTokens
import java.io.File
import java.util.IdentityHashMap

/** Declaration sites use the keyword; primary delegation uses its constructor header. */
@OptIn(org.jetbrains.kotlin.ir.symbols.UnsafeDuringIrConstructionAPI::class)
internal class SourceLocations(private val file: IrFile) {
  private val source by lazy { File(file.fileEntry.name).takeIf { it.isFile }?.readText() }
  private val primaryDelegations = IdentityHashMap<IrElement, IrConstructor>()

  fun primaryDelegation(
    constructor: IrConstructor,
    call: IrDelegatingConstructorCall,
  ) {
    if (constructor.isPrimary) primaryDelegations[call] = constructor
  }

  fun location(
    element: IrElement,
    owner: String,
  ): SourceLocation {
    val offset = declarationOffset(primaryDelegations[element] ?: element)
    return SourceLocation(
      owner,
      File(file.fileEntry.name).name,
      file.fileEntry.getLineNumber(offset) + 1,
      file.fileEntry.getColumnNumber(offset) + 1,
    )
  }

  private fun declarationOffset(element: IrElement): Int {
    val start = element.startOffset.coerceAtLeast(0)
    if (element.startOffset < 0) return start
    val keywords = declarationKeywords(element) ?: return start
    val text = source ?: return start
    if (start >= text.length || element.endOffset <= start) return start
    val lexer = KotlinLexer()
    lexer.start(text, start, element.endOffset.coerceAtMost(text.length))
    var depth = 0
    while (lexer.tokenType != null) {
      val token = lexer.tokenType
      if (depth == 0 && token in keywords) return lexer.tokenStart
      // Do not mistake keywords in annotation arguments for the annotated declaration.
      when (token) {
        KtTokens.LPAR, KtTokens.LBRACKET -> depth++
        KtTokens.RPAR, KtTokens.RBRACKET -> depth--
        KtTokens.LBRACE, KtTokens.EQ, KtTokens.SEMICOLON -> if (depth == 0) return start
      }
      if (depth < 0) return start
      lexer.advance()
    }
    return start
  }

  private fun declarationKeywords(element: IrElement): Set<org.jetbrains.kotlin.lexer.KtKeywordToken>? {
    val property = (element as? IrSimpleFunction)?.correspondingPropertySymbol?.owner
    return when {
      element is IrProperty || element is IrVariable || element is IrLocalDelegatedProperty ||
        property?.startOffset == element.startOffset ->
        setOf(KtTokens.VAL_KEYWORD, KtTokens.VAR_KEYWORD)
      element is IrSimpleFunction && property == null && !element.name.isSpecial -> setOf(KtTokens.FUN_KEYWORD)
      else -> null
    }
  }
}
