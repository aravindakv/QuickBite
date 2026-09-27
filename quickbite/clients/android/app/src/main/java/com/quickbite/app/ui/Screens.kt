package com.quickbite.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppScaffold(s: UiState, vm: AppViewModel, onLogin: () -> Unit, onLogout: () -> Unit) {
    Scaffold(topBar = {
        TopAppBar(title = { Text(if (s.user.isBlank()) "QuickBite" else "QuickBite · ${s.user}") },
            actions = { if (s.screen != Screen.Login) TextButton(onClick = onLogout) { Text("Logout") } })
    }) { pad ->
        Column(Modifier.padding(pad).padding(16.dp).fillMaxSize()) {
            if (s.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            s.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            when (s.screen) {
                Screen.Login -> Login(onLogin)
                Screen.Restaurants -> Restaurants(s, vm)
                Screen.Menu -> Menu(s, vm)
                Screen.Tracking -> Tracking(s, vm)
                Screen.Rider -> Rider(s, vm)
            }
        }
    }
}

@Composable private fun Login(onLogin: () -> Unit) {
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        Text("Log in as alice/alice (customer) or bob/bob (rider)")
        Spacer(Modifier.height(16.dp))
        Button(onClick = onLogin) { Text("Log in with QuickBite ID") }
    }
}

@Composable private fun Restaurants(s: UiState, vm: AppViewModel) {
    Row {
        listOf("blr" to "Bengaluru", "mum" to "Mumbai").forEach { (code, label) ->
            FilterChip(selected = s.city == code, onClick = { vm.loadRestaurants(code) }, label = { Text(label) })
            Spacer(Modifier.width(8.dp))
        }
    }
    LazyColumn {
        items(s.restaurants, key = { it.id }) { r ->
            Card(Modifier.fillMaxWidth().padding(vertical = 4.dp).clickable { vm.openRestaurant(r.id) }) {
                Column(Modifier.padding(12.dp)) {
                    Text(r.name, style = MaterialTheme.typography.titleMedium)
                    Text("${r.cuisine} · ${r.area ?: ""} · ★ ${r.rating} · ${r.avgPrepMinutes} min")
                }
            }
        }
    }
}

@Composable private fun Menu(s: UiState, vm: AppViewModel) {
    val d = s.detail ?: return
    Column(Modifier.fillMaxSize()) {
        TextButton(onClick = vm::back) { Text("← Restaurants") }
        Text(d.restaurant.name, style = MaterialTheme.typography.headlineSmall)
        LazyColumn(Modifier.weight(1f)) {
            items(d.menu, key = { it.id }) { m ->
                Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(m.name + if (m.veg) " 🟢" else " 🔴")
                        Text(if (m.available) "₹%.0f".format(m.price) else "Sold out", style = MaterialTheme.typography.bodySmall)
                    }
                    // Sold-out items stay tappable ON PURPOSE in debug builds: it lets you test the server's 409.
                    OutlinedButton(onClick = { vm.changeQty(m.id, -1) }) { Text("−") }
                    Text("${s.cart[m.id] ?: 0}", Modifier.padding(horizontal = 8.dp))
                    OutlinedButton(onClick = { vm.changeQty(m.id, +1) }) { Text("+") }
                }
            }
        }
        CardPicker(s, vm)
        val total = d.menu.sumOf { (s.cart[it.id] ?: 0) * it.price }
        Button(onClick = vm::placeOrder, enabled = s.cart.isNotEmpty() && !s.busy, modifier = Modifier.fillMaxWidth()) {
            Text("Place order · ₹%.0f (final price set by server)".format(total))
        }
    }
}

@Composable private fun Tracking(s: UiState, vm: AppViewModel) {
    val o = s.order ?: return
    LaunchedEffect(o.id) { vm.loadTrack(o.id) }      // backfill on (re)entry; needs androidx.compose.runtime.LaunchedEffect
    Column(Modifier.fillMaxSize()) {
        Text("Order ${o.id}", style = MaterialTheme.typography.titleMedium)
        Text("Status: ${o.status}", style = MaterialTheme.typography.headlineSmall)
        s.payment?.let { p ->
            Text("Payment: ${p.status} · ${p.cardBrand ?: ""} ••${p.cardLast4 ?: ""}")
            p.failureReason?.let { Text("Reason: $it", color = MaterialTheme.colorScheme.error) }
        }
        Spacer(Modifier.height(8.dp))
        MapPane(destination = o.deliveryLat to o.deliveryLon, rider = s.riderPos, path = s.riderPath)
        s.etaMinutes?.let { eta ->
            Text("Arriving in ~$eta min" + (s.distanceKm?.let { " · %.1f km away".format(it) } ?: ""),
                style = MaterialTheme.typography.titleMedium)
        }
        Spacer(Modifier.height(8.dp))
        LazyColumn(Modifier.weight(1f)) { items(s.log.reversed()) { Text(it, style = MaterialTheme.typography.bodySmall) } }
        if (o.status == "DELIVERED" || o.status == "CANCELLED") Button(onClick = vm::back) { Text("Order again") }
    }
}

@Composable private fun Rider(s: UiState, vm: AppViewModel) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(if (s.riderOnline) "You are ONLINE" else "You are offline", Modifier.weight(1f))
        Switch(checked = s.riderOnline, onCheckedChange = { vm.toggleOnline() })
    }
    val o = s.riderOrder
    if (o == null) { Text("Waiting for an order…"); return }
    Text("Order ${o.id} · ${o.status}", style = MaterialTheme.typography.titleMedium)
    MapPane(destination = o.deliveryLat to o.deliveryLon, rider = s.riderPos)
    Row {
        Button(onClick = vm::pickup, enabled = o.status == "RIDER_ASSIGNED") { Text("Picked up") }
        Spacer(Modifier.width(8.dp))
        Button(onClick = vm::deliver, enabled = o.status == "PICKED_UP") { Text("Delivered") }
    }
}