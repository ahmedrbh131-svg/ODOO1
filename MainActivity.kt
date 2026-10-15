package com.example.odoomobile

import android.content.ContentValues
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.OutputStream

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { MaterialTheme { OdooApp() } }
    }
}

@Composable
private fun OdooApp() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var url by remember { mutableStateOf("") }
    var db by remember { mutableStateOf("") }
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var client by remember { mutableStateOf<OdooClient?>(null) }
    var connectedAs by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf("") }
    var tab by remember { mutableIntStateOf(0) }

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        if (client == null) {
            Column(
                Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
                verticalArrangement = Arrangement.Center
            ) {
                Text("Odoo Mobile", style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold)
                Text("Transferts et produits", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(24.dp))
                OutlinedTextField(url, { url = it }, label = { Text("Adresse HTTPS de Odoo") }, placeholder = { Text("https://odoo.votre-domaine.com") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                OutlinedTextField(db, { db = it }, label = { Text("Base de données") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                OutlinedTextField(username, { username = it }, label = { Text("Utilisateur / e-mail") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                OutlinedTextField(password, { password = it }, label = { Text("Mot de passe Odoo") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                Spacer(Modifier.height(16.dp))
                Button(
                    enabled = !busy && url.isNotBlank() && db.isNotBlank() && username.isNotBlank() && password.isNotBlank(),
                    onClick = {
                        busy = true; message = ""
                        scope.launch {
                            try {
                                val c = OdooClient(OdooConfig(url, db, username, password))
                                connectedAs = withContext(Dispatchers.IO) { c.login() }
                                client = c
                            } catch (e: Exception) { message = e.message ?: "Échec de connexion" }
                            finally { busy = false }
                        }
                    }, modifier = Modifier.fillMaxWidth()
                ) { Text(if (busy) "Connexion..." else "Se connecter") }
                if (message.isNotBlank()) Text(message, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 12.dp))
                Spacer(Modifier.height(12.dp))
                Text("Sécurité : l'accès depuis Internet exige une URL HTTPS. Ne saisissez pas votre mot de passe dans une adresse URL.", style = MaterialTheme.typography.bodySmall)
            }
        } else {
            Column(Modifier.fillMaxSize()) {
                Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp)) {
                    Text("Odoo Mobile", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                    Text("Connecté : $connectedAs", style = MaterialTheme.typography.bodySmall)
                }
                TabRow(selectedTabIndex = tab) {
                    Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text("Transferts") })
                    Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text("Produits") })
                }
                if (tab == 0) TransferScreen(client!!, scope, { busy = it }, { message = it }, { message }, { savePdf(context, it.first, it.second) })
                else ProductScreen(client!!, scope, { busy = it }, { message = it }, { message })
                if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                if (message.isNotBlank()) Text(message, Modifier.fillMaxWidth().padding(12.dp), color = MaterialTheme.colorScheme.primary)
                TextButton(onClick = { client = null; connectedAs = ""; message = "" }, modifier = Modifier.align(Alignment.End)) { Text("Déconnexion") }
            }
        }
    }
}

@Composable
private fun TransferScreen(
    client: OdooClient,
    scope: kotlinx.coroutines.CoroutineScope,
    setBusy: (Boolean) -> Unit,
    setMessage: (String) -> Unit,
    getMessage: () -> String,
    onPdf: (Pair<String, ByteArray>) -> Unit
) {
    var query by remember { mutableStateOf("") }
    var transfers by remember { mutableStateOf<List<OdooTransfer>>(emptyList()) }
    var lines by remember { mutableStateOf("") }
    var locations by remember { mutableStateOf<List<Pair<Int, String>>>(emptyList()) }
    var sourceId by remember { mutableIntStateOf(0) }
    var destinationId by remember { mutableIntStateOf(0) }
    var sourceExpanded by remember { mutableStateOf(false) }
    var destExpanded by remember { mutableStateOf(false) }
    var selectedTransfer by remember { mutableStateOf<OdooTransfer?>(null) }

    LaunchedEffect(client) {
        try { locations = withContext(Dispatchers.IO) { client.listLocations() } }
        catch (e: Exception) { setMessage("Chargement des emplacements : ${e.message}") }
    }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Rechercher un transfert", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        OutlinedTextField(query, { query = it }, label = { Text("Numéro du transfert") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
        Button(onClick = {
            setBusy(true)
            scope.launch {
                try { transfers = withContext(Dispatchers.IO) { client.searchTransfers(query) }; setMessage("${transfers.size} transfert(s) trouvé(s).") }
                catch (e: Exception) { setMessage(e.message ?: "Erreur") }
                finally { setBusy(false) }
            }
        }, modifier = Modifier.fillMaxWidth()) { Text("Rechercher") }
        transfers.forEach { transfer ->
            ElevatedCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(transfer.name, fontWeight = FontWeight.Bold)
                    Text("État : ${transfer.state} • ${transfer.source} → ${transfer.destination}", style = MaterialTheme.typography.bodySmall)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { selectedTransfer = transfer }) { Text("Imprimer PDF") }
                    }
                }
            }
        }
        selectedTransfer?.let { t ->
            LaunchedEffect(t.id) {
                setBusy(true)
                try {
                    val pdf = withContext(Dispatchers.IO) { client.downloadPickingPdf(t.id) }
                    onPdf(t.name to pdf)
                    setMessage("PDF de ${t.name} téléchargé.")
                } catch (e: Exception) { setMessage("Impression ${t.name} : ${e.message}") }
                finally { setBusy(false); selectedTransfer = null }
            }
        }
        HorizontalDivider()
        Text("Créer un transfert interne", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Text("Une ligne par produit au format : référence-ou-code-barres;quantité", style = MaterialTheme.typography.bodySmall)
        OutlinedTextField(lines, { lines = it }, label = { Text("Ex. ART123;2,5") }, modifier = Modifier.fillMaxWidth(), minLines = 3)
        ExposedDropdownMenuBox(expanded = sourceExpanded, onExpandedChange = { sourceExpanded = it }) {
            OutlinedTextField(
                value = locations.firstOrNull { it.first == sourceId }?.second.orEmpty(),
                onValueChange = {}, readOnly = true, label = { Text("Emplacement source") },
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(sourceExpanded) },
                modifier = Modifier.menuAnchor().fillMaxWidth()
            )
            ExposedDropdownMenu(expanded = sourceExpanded, onDismissRequest = { sourceExpanded = false }) {
                locations.forEach { loc -> DropdownMenuItem(text = { Text(loc.second) }, onClick = { sourceId = loc.first; sourceExpanded = false }) }
            }
        }
        ExposedDropdownMenuBox(expanded = destExpanded, onExpandedChange = { destExpanded = it }) {
            OutlinedTextField(
                value = locations.firstOrNull { it.first == destinationId }?.second.orEmpty(),
                onValueChange = {}, readOnly = true, label = { Text("Emplacement destination") },
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(destExpanded) },
                modifier = Modifier.menuAnchor().fillMaxWidth()
            )
            ExposedDropdownMenu(expanded = destExpanded, onDismissRequest = { destExpanded = false }) {
                locations.forEach { loc -> DropdownMenuItem(text = { Text(loc.second) }, onClick = { destinationId = loc.first; destExpanded = false }) }
            }
        }
        Button(onClick = {
            setBusy(true)
            scope.launch {
                try {
                    val name = withContext(Dispatchers.IO) { client.createInternalTransfer(lines, sourceId, destinationId) }
                    setMessage("Transfert créé : $name")
                    query = name
                    transfers = withContext(Dispatchers.IO) { client.searchTransfers(name) }
                    lines = ""
                } catch (e: Exception) { setMessage("Création impossible : ${e.message}") }
                finally { setBusy(false) }
            }
        }, modifier = Modifier.fillMaxWidth()) { Text("Créer le transfert") }
    }
}

@Composable
private fun ProductScreen(
    client: OdooClient,
    scope: kotlinx.coroutines.CoroutineScope,
    setBusy: (Boolean) -> Unit,
    setMessage: (String) -> Unit,
    getMessage: () -> String
) {
    var query by remember { mutableStateOf("") }
    var products by remember { mutableStateOf<List<OdooProduct>>(emptyList()) }
    var selected by remember { mutableStateOf<OdooProduct?>(null) }
    var name by remember { mutableStateOf("") }
    var barcode by remember { mutableStateOf("") }
    var price by remember { mutableStateOf("") }

    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Text("Recherche produit", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        OutlinedTextField(query, { query = it }, label = { Text("Nom, référence interne ou code-barres") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
        Button(onClick = {
            setBusy(true)
            scope.launch {
                try { products = withContext(Dispatchers.IO) { client.searchProducts(query) }; setMessage("${products.size} produit(s) trouvé(s).") }
                catch (e: Exception) { setMessage(e.message ?: "Erreur") }
                finally { setBusy(false) }
            }
        }, modifier = Modifier.fillMaxWidth()) { Text("Rechercher") }
        LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(products, key = { it.id }) { p ->
                ElevatedCard(onClick = { selected = p; name = p.name; barcode = p.barcode; price = p.salePrice.toString() }, modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp)) {
                        Text(p.name, fontWeight = FontWeight.Bold)
                        Text("Réf. : ${p.ref.ifBlank { "—" }} • EAN : ${p.barcode.ifBlank { "—" }}")
                        Text("Prix de vente : ${p.salePrice}")
                    }
                }
            }
        }
        selected?.let { p ->
            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            Text("Modifier : ${p.ref}", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            OutlinedTextField(name, { name = it }, label = { Text("Nom") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
            OutlinedTextField(barcode, { barcode = it }, label = { Text("Code-barres") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
            OutlinedTextField(price, { price = it }, label = { Text("Prix de vente") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = {
                    val amount = price.replace(',', '.').toDoubleOrNull()
                    if (amount == null) { setMessage("Prix de vente invalide."); return@Button }
                    setBusy(true)
                    scope.launch {
                        try {
                            withContext(Dispatchers.IO) { client.updateProduct(p, name, barcode, amount) }
                            setMessage("Produit mis à jour : ${p.ref}")
                            selected = null
                            products = withContext(Dispatchers.IO) { client.searchProducts(query) }
                        } catch (e: Exception) { setMessage("Modification impossible : ${e.message}") }
                        finally { setBusy(false) }
                    }
                }) { Text("Enregistrer") }
                OutlinedButton(onClick = { selected = null }) { Text("Annuler") }
            }
        }
    }
}

private fun savePdf(context: android.content.Context, filename: String, bytes: ByteArray) {
    val safeName = filename.replace(Regex("[^A-Za-z0-9._-]"), "_") + ".pdf"
    val resolver = context.contentResolver
    val values = ContentValues().apply {
        put(MediaStore.Downloads.DISPLAY_NAME, safeName)
        put(MediaStore.Downloads.MIME_TYPE, "application/pdf")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) put(MediaStore.Downloads.IS_PENDING, 1)
    }
    val uri: Uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
        ?: throw IllegalStateException("Impossible d'enregistrer le PDF dans Téléchargements.")
    resolver.openOutputStream(uri)?.use { it.write(bytes) } ?: throw IllegalStateException("Écriture du PDF impossible.")
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        values.clear(); values.put(MediaStore.Downloads.IS_PENDING, 0)
        resolver.update(uri, values, null, null)
    }
}
