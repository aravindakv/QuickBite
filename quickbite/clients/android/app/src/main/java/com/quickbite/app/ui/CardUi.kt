package com.quickbite.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp

@Composable
fun CardPicker(s: UiState, vm: AppViewModel) {
    Text("Pay with", style = MaterialTheme.typography.titleSmall)
    LazyRow {
        items(s.cards, key = { it.id }) { c ->
            FilterChip(
                selected = s.selectedCardId == c.id,
                onClick = { vm.selectCard(c.id) },
                label = { Text("${c.brand} ••${c.last4}" + if (c.isDefault) " ★" else "") })
            Spacer(Modifier.width(6.dp))
        }
        item { AssistChip(onClick = { vm.showAddCard(true) }, label = { Text("+ Add card") }) }
    }
    if (s.showAddCard) AddCardDialog(s, vm)
}

@Composable
private fun AddCardDialog(s: UiState, vm: AppViewModel) {
    var number by remember { mutableStateOf("") }
    var expiry by remember { mutableStateOf("12/30") }
    var cvc by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = { vm.showAddCard(false) },
        title = { Text("Add card") },
        text = {
            Column {
                OutlinedTextField(number, { number = it.filter { ch -> ch.isDigit() || ch == ' ' }.take(23) },
                    label = { Text("Card number") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                Row {
                    OutlinedTextField(expiry, { expiry = it.take(5) }, label = { Text("MM/YY") },
                        singleLine = true, modifier = Modifier.weight(1f))
                    Spacer(Modifier.width(8.dp))
                    OutlinedTextField(cvc, { cvc = it.filter(Char::isDigit).take(4) }, label = { Text("CVC") },
                        singleLine = true, visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                        modifier = Modifier.weight(1f))
                }
                s.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }   // e.g. "Invalid card number"
                if (s.testCards.isNotEmpty()) {                                   // DEBUG builds only
                    Spacer(Modifier.height(8.dp))
                    Text("Test cards (tap to fill)", style = MaterialTheme.typography.labelMedium)
                    Column(Modifier.heightIn(max = 180.dp)) {
                        s.testCards.forEach { t ->
                            TextButton(onClick = {
                                number = t.number.chunked(4).joinToString(" ")
                                cvc = if (t.brand == "AMEX") "1234" else "123"
                            }) { Text("••${t.number.takeLast(4)} ${t.brand}: ${t.description}") }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(enabled = !s.busy, onClick = {
                val (mm, yy) = expiry.split("/").map { it.trim().toIntOrNull() ?: 0 }.let { it[0] to (it.getOrElse(1) { 0 }) }
                vm.addCard(number.replace(" ", ""), mm, 2000 + yy, cvc)          // Validated again by the PSP (Luhn, expiry, CVC)
            }) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = { vm.showAddCard(false) }) { Text("Cancel") } })
}