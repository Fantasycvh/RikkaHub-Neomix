package app.androidrik.rikkahub.ui.components.ui

import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import app.androidrik.rikkahub.ui.components.ui.AppTextField as TextField
import androidx.compose.material3.TextFieldColors
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType

@Composable
fun <T : Number> OutlinedNumberInput(
    value: T,
    onValueChange: (T) -> Unit,
    modifier: Modifier = Modifier,
    label: String = "",
    colors: TextFieldColors = OutlinedTextFieldDefaults.colors()
) {
    var lastCommitted by remember { mutableStateOf(value) }
    var textFieldValue by remember { mutableStateOf(value.toString()) }
    LaunchedEffect(value) {
        if (value != lastCommitted) {
            lastCommitted = value
            val formatted = value.toString()
            if (textFieldValue != formatted) textFieldValue = formatted
        }
    }
    AppOutlinedTextField(
        modifier = modifier,
        value = textFieldValue,
        onValueChange = { newValue ->
            textFieldValue = newValue
            if (newValue.isValidNumberInput()) {
                try {
                    @Suppress("UNCHECKED_CAST")
                    val newVal = when (value) {
                        is Int -> newValue.toInt() as T
                        is Float -> newValue.toFloat() as T
                        is Double -> newValue.toDouble() as T
                        else -> throw IllegalArgumentException("Unsupported number type")
                    }
                    lastCommitted = newVal
                    onValueChange(newVal)
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        },
        label = { Text(label) },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        isError = !textFieldValue.isValidNumberInput(),
        colors = colors
    )
}

@Composable
fun <T : Number> NumberInput(
    value: T,
    onValueChange: (T) -> Unit,
    modifier: Modifier = Modifier,
    label: String = "",
    colors: TextFieldColors = TextFieldDefaults.colors()
) {
    var lastCommitted by remember { mutableStateOf(value) }
    var textFieldValue by remember { mutableStateOf(value.toString()) }
    LaunchedEffect(value) {
        if (value != lastCommitted) {
            lastCommitted = value
            val formatted = value.toString()
            if (textFieldValue != formatted) textFieldValue = formatted
        }
    }
    TextField(
        shape = appSkinInputShape(),
        modifier = modifier,
        value = textFieldValue,
        onValueChange = { newValue ->
            textFieldValue = newValue
            if (newValue.isValidNumberInput()) {
                try {
                    @Suppress("UNCHECKED_CAST")
                    val newVal = when (value) {
                        is Int -> newValue.toInt() as T
                        is Float -> newValue.toFloat() as T
                        is Double -> newValue.toDouble() as T
                        else -> throw IllegalArgumentException("Unsupported number type")
                    }
                    lastCommitted = newVal
                    onValueChange(newVal)
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        },
        label = { Text(label) },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        isError = !textFieldValue.isValidNumberInput(),
        colors = colors
    )
}

private val NumberRegex = Regex("^[+-]?\\d+(\\.\\d+)?$")
private fun String.isValidNumberInput() = this.isNotEmpty() && NumberRegex.matches(this)
