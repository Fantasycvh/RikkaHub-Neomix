package app.androidrik.rikkahub.ui.components.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import app.androidrik.rikkahub.ui.theme.LocalAppSkin

@Composable
fun appSkinButtonShape(): Shape = LocalAppSkin.current.skin.shapes.buttonDp?.let { RoundedCornerShape(it.dp) } ?: ButtonDefaults.shape

@Composable
fun appSkinInputShape(): Shape = RoundedCornerShape(LocalAppSkin.current.skin.shapes.inputDp.dp)

/** Filled fields retain Material's square bottom edge for the indicator line. */
@Composable
fun appSkinFilledInputShape(): Shape = LocalAppSkin.current.skin.shapes.inputDp.dp.let {
    RoundedCornerShape(topStart = it, topEnd = it)
}

/** Retains the host's size-dependent pressed morph when no button override is selected. */
@Composable
fun appSkinButtonShapes(height: Dp? = null): ButtonShapes {
    val radius = LocalAppSkin.current.skin.shapes.buttonDp
    return if (radius == null) {
        if (height == null) ButtonDefaults.shapes() else ButtonDefaults.shapesFor(height)
    } else ButtonShapes(RoundedCornerShape(radius.dp), RoundedCornerShape((radius * .7f).dp))
}

/** Host-owned behavior and accessibility stay in Material; skins only choose visual defaults. */
@Composable
fun AppButton(
    onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true,
    shape: Shape = appSkinButtonShape(), colors: ButtonColors = ButtonDefaults.buttonColors(),
    elevation: ButtonElevation? = ButtonDefaults.buttonElevation(), border: BorderStroke? = null,
    contentPadding: PaddingValues = ButtonDefaults.ContentPadding,
    interactionSource: MutableInteractionSource? = null, content: @Composable RowScope.() -> Unit,
) = Button(onClick, modifier, enabled, shape, colors, elevation, border, contentPadding, interactionSource, content)

@Composable
fun AppOutlinedButton(
    onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true,
    shape: Shape = appSkinButtonShape(), colors: ButtonColors = ButtonDefaults.outlinedButtonColors(),
    elevation: ButtonElevation? = null, border: BorderStroke? = ButtonDefaults.outlinedButtonBorder(enabled),
    contentPadding: PaddingValues = ButtonDefaults.ContentPadding,
    interactionSource: MutableInteractionSource? = null, content: @Composable RowScope.() -> Unit,
) = OutlinedButton(onClick, modifier, enabled, shape, colors, elevation, border, contentPadding, interactionSource, content)

@Composable
fun AppTextButton(
    onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true,
    shape: Shape = appSkinButtonShape(), colors: ButtonColors = ButtonDefaults.textButtonColors(),
    elevation: ButtonElevation? = null, border: BorderStroke? = null,
    contentPadding: PaddingValues = ButtonDefaults.TextButtonContentPadding,
    interactionSource: MutableInteractionSource? = null, content: @Composable RowScope.() -> Unit,
) = TextButton(onClick, modifier, enabled, shape, colors, elevation, border, contentPadding, interactionSource, content)

@Composable
fun AppFilledTonalButton(
    onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true,
    shape: Shape = appSkinButtonShape(), colors: ButtonColors = ButtonDefaults.filledTonalButtonColors(),
    elevation: ButtonElevation? = ButtonDefaults.filledTonalButtonElevation(), border: BorderStroke? = null,
    contentPadding: PaddingValues = ButtonDefaults.ContentPadding,
    interactionSource: MutableInteractionSource? = null, content: @Composable RowScope.() -> Unit,
) = FilledTonalButton(onClick, modifier, enabled, shape, colors, elevation, border, contentPadding, interactionSource, content)

@Composable
fun AppOutlinedTextField(
    value: String, onValueChange: (String) -> Unit, modifier: Modifier = Modifier,
    enabled: Boolean = true, readOnly: Boolean = false, textStyle: TextStyle = LocalTextStyle.current,
    label: @Composable (() -> Unit)? = null, placeholder: @Composable (() -> Unit)? = null,
    leadingIcon: @Composable (() -> Unit)? = null, trailingIcon: @Composable (() -> Unit)? = null,
    prefix: @Composable (() -> Unit)? = null, suffix: @Composable (() -> Unit)? = null,
    supportingText: @Composable (() -> Unit)? = null, isError: Boolean = false,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default, keyboardActions: KeyboardActions = KeyboardActions.Default,
    singleLine: Boolean = false, maxLines: Int = if (singleLine) 1 else Int.MAX_VALUE, minLines: Int = 1,
    interactionSource: MutableInteractionSource? = null, shape: Shape = appSkinInputShape(),
    colors: TextFieldColors = OutlinedTextFieldDefaults.colors(),
) = OutlinedTextField(value = value, onValueChange = onValueChange, modifier = modifier, enabled = enabled,
    readOnly = readOnly, textStyle = textStyle, label = label, placeholder = placeholder, leadingIcon = leadingIcon,
    trailingIcon = trailingIcon, prefix = prefix, suffix = suffix, supportingText = supportingText, isError = isError,
    visualTransformation = visualTransformation, keyboardOptions = keyboardOptions, keyboardActions = keyboardActions,
    singleLine = singleLine, maxLines = maxLines, minLines = minLines, interactionSource = interactionSource, shape = shape, colors = colors)

@Composable
fun AppOutlinedTextField(
    value: TextFieldValue, onValueChange: (TextFieldValue) -> Unit, modifier: Modifier = Modifier,
    enabled: Boolean = true, readOnly: Boolean = false, textStyle: TextStyle = LocalTextStyle.current,
    label: @Composable (() -> Unit)? = null, placeholder: @Composable (() -> Unit)? = null,
    leadingIcon: @Composable (() -> Unit)? = null, trailingIcon: @Composable (() -> Unit)? = null,
    prefix: @Composable (() -> Unit)? = null, suffix: @Composable (() -> Unit)? = null,
    supportingText: @Composable (() -> Unit)? = null, isError: Boolean = false,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default, keyboardActions: KeyboardActions = KeyboardActions.Default,
    singleLine: Boolean = false, maxLines: Int = if (singleLine) 1 else Int.MAX_VALUE, minLines: Int = 1,
    interactionSource: MutableInteractionSource? = null, shape: Shape = appSkinInputShape(),
    colors: TextFieldColors = OutlinedTextFieldDefaults.colors(),
) = OutlinedTextField(value = value, onValueChange = onValueChange, modifier = modifier, enabled = enabled,
    readOnly = readOnly, textStyle = textStyle, label = label, placeholder = placeholder, leadingIcon = leadingIcon,
    trailingIcon = trailingIcon, prefix = prefix, suffix = suffix, supportingText = supportingText, isError = isError,
    visualTransformation = visualTransformation, keyboardOptions = keyboardOptions, keyboardActions = keyboardActions,
    singleLine = singleLine, maxLines = maxLines, minLines = minLines, interactionSource = interactionSource, shape = shape, colors = colors)

@Composable
fun AppTextField(
    value: String, onValueChange: (String) -> Unit, modifier: Modifier = Modifier,
    enabled: Boolean = true, readOnly: Boolean = false, textStyle: TextStyle = LocalTextStyle.current,
    label: @Composable (() -> Unit)? = null, placeholder: @Composable (() -> Unit)? = null,
    leadingIcon: @Composable (() -> Unit)? = null, trailingIcon: @Composable (() -> Unit)? = null,
    prefix: @Composable (() -> Unit)? = null, suffix: @Composable (() -> Unit)? = null,
    supportingText: @Composable (() -> Unit)? = null, isError: Boolean = false,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default, keyboardActions: KeyboardActions = KeyboardActions.Default,
    singleLine: Boolean = false, maxLines: Int = if (singleLine) 1 else Int.MAX_VALUE, minLines: Int = 1,
    interactionSource: MutableInteractionSource? = null, shape: Shape = appSkinFilledInputShape(),
    colors: TextFieldColors = TextFieldDefaults.colors(),
) = TextField(value = value, onValueChange = onValueChange, modifier = modifier, enabled = enabled,
    readOnly = readOnly, textStyle = textStyle, label = label, placeholder = placeholder, leadingIcon = leadingIcon,
    trailingIcon = trailingIcon, prefix = prefix, suffix = suffix, supportingText = supportingText, isError = isError,
    visualTransformation = visualTransformation, keyboardOptions = keyboardOptions, keyboardActions = keyboardActions,
    singleLine = singleLine, maxLines = maxLines, minLines = minLines, interactionSource = interactionSource, shape = shape, colors = colors)

@Composable
fun AppTextField(
    value: TextFieldValue, onValueChange: (TextFieldValue) -> Unit, modifier: Modifier = Modifier,
    enabled: Boolean = true, readOnly: Boolean = false, textStyle: TextStyle = LocalTextStyle.current,
    label: @Composable (() -> Unit)? = null, placeholder: @Composable (() -> Unit)? = null,
    leadingIcon: @Composable (() -> Unit)? = null, trailingIcon: @Composable (() -> Unit)? = null,
    prefix: @Composable (() -> Unit)? = null, suffix: @Composable (() -> Unit)? = null,
    supportingText: @Composable (() -> Unit)? = null, isError: Boolean = false,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default, keyboardActions: KeyboardActions = KeyboardActions.Default,
    singleLine: Boolean = false, maxLines: Int = if (singleLine) 1 else Int.MAX_VALUE, minLines: Int = 1,
    interactionSource: MutableInteractionSource? = null, shape: Shape = appSkinFilledInputShape(),
    colors: TextFieldColors = TextFieldDefaults.colors(),
) = TextField(value = value, onValueChange = onValueChange, modifier = modifier, enabled = enabled,
    readOnly = readOnly, textStyle = textStyle, label = label, placeholder = placeholder, leadingIcon = leadingIcon,
    trailingIcon = trailingIcon, prefix = prefix, suffix = suffix, supportingText = supportingText, isError = isError,
    visualTransformation = visualTransformation, keyboardOptions = keyboardOptions, keyboardActions = keyboardActions,
    singleLine = singleLine, maxLines = maxLines, minLines = minLines, interactionSource = interactionSource, shape = shape, colors = colors)


@Composable
fun AppButton(
    onClick: () -> Unit, shapes: ButtonShapes, modifier: Modifier = Modifier, enabled: Boolean = true,
    colors: ButtonColors = ButtonDefaults.buttonColors(), elevation: ButtonElevation? = ButtonDefaults.buttonElevation(),
    border: BorderStroke? = null, contentPadding: PaddingValues = ButtonDefaults.ContentPadding,
    interactionSource: MutableInteractionSource? = null, content: @Composable RowScope.() -> Unit,
) = Button(onClick, shapes, modifier, enabled, colors, elevation, border, contentPadding, interactionSource, content)

@Composable
fun AppFilledTonalButton(
    onClick: () -> Unit, shapes: ButtonShapes, modifier: Modifier = Modifier, enabled: Boolean = true,
    colors: ButtonColors = ButtonDefaults.filledTonalButtonColors(), elevation: ButtonElevation? = ButtonDefaults.filledTonalButtonElevation(),
    border: BorderStroke? = null, contentPadding: PaddingValues = ButtonDefaults.ContentPadding,
    interactionSource: MutableInteractionSource? = null, content: @Composable RowScope.() -> Unit,
) = FilledTonalButton(onClick, shapes, modifier, enabled, colors, elevation, border, contentPadding, interactionSource, content)

@Composable
fun AppOutlinedButton(
    onClick: () -> Unit, shapes: ButtonShapes, modifier: Modifier = Modifier, enabled: Boolean = true,
    colors: ButtonColors = ButtonDefaults.outlinedButtonColors(), elevation: ButtonElevation? = null,
    border: BorderStroke? = ButtonDefaults.outlinedButtonBorder(enabled), contentPadding: PaddingValues = ButtonDefaults.ContentPadding,
    interactionSource: MutableInteractionSource? = null, content: @Composable RowScope.() -> Unit,
) = OutlinedButton(onClick, shapes, modifier, enabled, colors, elevation, border, contentPadding, interactionSource, content)

@Composable
fun AppTextButton(
    onClick: () -> Unit, shapes: ButtonShapes, modifier: Modifier = Modifier, enabled: Boolean = true,
    colors: ButtonColors = ButtonDefaults.textButtonColors(), elevation: ButtonElevation? = null,
    border: BorderStroke? = null, contentPadding: PaddingValues = ButtonDefaults.TextButtonContentPadding,
    interactionSource: MutableInteractionSource? = null, content: @Composable RowScope.() -> Unit,
) = TextButton(onClick, shapes, modifier, enabled, colors, elevation, border, contentPadding, interactionSource, content)

/** The caller's editing state, transformations, scroll and IME callbacks pass through unchanged. */
@Composable
fun AppOutlinedTextField(
    state: TextFieldState, modifier: Modifier = Modifier, enabled: Boolean = true, readOnly: Boolean = false,
    textStyle: TextStyle = LocalTextStyle.current,
    labelPosition: TextFieldLabelPosition = TextFieldLabelPosition.Attached(),
    label: @Composable (TextFieldLabelScope.() -> Unit)? = null,
    placeholder: @Composable (() -> Unit)? = null, leadingIcon: @Composable (() -> Unit)? = null,
    trailingIcon: @Composable (() -> Unit)? = null, prefix: @Composable (() -> Unit)? = null,
    suffix: @Composable (() -> Unit)? = null, supportingText: @Composable (() -> Unit)? = null,
    isError: Boolean = false, inputTransformation: InputTransformation? = null,
    outputTransformation: OutputTransformation? = null, keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    onKeyboardAction: KeyboardActionHandler? = null, lineLimits: TextFieldLineLimits = TextFieldLineLimits.Default,
    onTextLayout: (Density.(() -> TextLayoutResult?) -> Unit)? = null,
    scrollState: ScrollState = rememberScrollState(), shape: Shape = appSkinInputShape(),
    colors: TextFieldColors = OutlinedTextFieldDefaults.colors(),
    contentPadding: PaddingValues = OutlinedTextFieldDefaults.contentPadding(),
    interactionSource: MutableInteractionSource? = null,
) = OutlinedTextField(state = state, modifier = modifier, enabled = enabled, readOnly = readOnly,
    textStyle = textStyle, labelPosition = labelPosition, label = label, placeholder = placeholder,
    leadingIcon = leadingIcon, trailingIcon = trailingIcon, prefix = prefix, suffix = suffix,
    supportingText = supportingText, isError = isError, inputTransformation = inputTransformation,
    outputTransformation = outputTransformation, keyboardOptions = keyboardOptions, onKeyboardAction = onKeyboardAction,
    lineLimits = lineLimits, onTextLayout = onTextLayout, scrollState = scrollState, shape = shape,
    colors = colors, contentPadding = contentPadding, interactionSource = interactionSource)

/** The caller's editing state, transformations, scroll and IME callbacks pass through unchanged. */
@Composable
fun AppTextField(
    state: TextFieldState, modifier: Modifier = Modifier, enabled: Boolean = true, readOnly: Boolean = false,
    textStyle: TextStyle = LocalTextStyle.current,
    labelPosition: TextFieldLabelPosition = TextFieldLabelPosition.Attached(),
    label: @Composable (TextFieldLabelScope.() -> Unit)? = null,
    placeholder: @Composable (() -> Unit)? = null, leadingIcon: @Composable (() -> Unit)? = null,
    trailingIcon: @Composable (() -> Unit)? = null, prefix: @Composable (() -> Unit)? = null,
    suffix: @Composable (() -> Unit)? = null, supportingText: @Composable (() -> Unit)? = null,
    isError: Boolean = false, inputTransformation: InputTransformation? = null,
    outputTransformation: OutputTransformation? = null, keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    onKeyboardAction: KeyboardActionHandler? = null, lineLimits: TextFieldLineLimits = TextFieldLineLimits.Default,
    onTextLayout: (Density.(() -> TextLayoutResult?) -> Unit)? = null,
    scrollState: ScrollState = rememberScrollState(), shape: Shape = appSkinFilledInputShape(),
    colors: TextFieldColors = TextFieldDefaults.colors(),
    contentPadding: PaddingValues = if (label == null || labelPosition is TextFieldLabelPosition.Above) TextFieldDefaults.contentPaddingWithoutLabel() else TextFieldDefaults.contentPaddingWithLabel(),
    interactionSource: MutableInteractionSource? = null,
) = TextField(state = state, modifier = modifier, enabled = enabled, readOnly = readOnly,
    textStyle = textStyle, labelPosition = labelPosition, label = label, placeholder = placeholder,
    leadingIcon = leadingIcon, trailingIcon = trailingIcon, prefix = prefix, suffix = suffix,
    supportingText = supportingText, isError = isError, inputTransformation = inputTransformation,
    outputTransformation = outputTransformation, keyboardOptions = keyboardOptions, onKeyboardAction = onKeyboardAction,
    lineLimits = lineLimits, onTextLayout = onTextLayout, scrollState = scrollState, shape = shape,
    colors = colors, contentPadding = contentPadding, interactionSource = interactionSource)
