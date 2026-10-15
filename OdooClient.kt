package com.example.odoomobile

import okhttp3.JavaNetCookieJar
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.net.CookieManager
import java.net.CookiePolicy
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

data class OdooConfig(val url: String, val database: String, val username: String, val password: String)
data class OdooProduct(val id: Int, val name: String, val ref: String, val barcode: String, val salePrice: Double, val templateId: Int)
data class OdooTransfer(val id: Int, val name: String, val state: String, val source: String, val destination: String, val scheduled: String)

class OdooClient(private val config: OdooConfig) {
    private val cookies = CookieManager().apply { setCookiePolicy(CookiePolicy.ACCEPT_ALL) }
    private val http = OkHttpClient.Builder()
        .cookieJar(JavaNetCookieJar(cookies))
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(45, TimeUnit.SECONDS)
        .writeTimeout(45, TimeUnit.SECONDS)
        .build()
    private val ids = AtomicInteger(1)
    private val jsonType = "application/json; charset=utf-8".toMediaType()
    private var uid: Int? = null

    private fun normalizedUrl(): String = config.url.trim().trimEnd('/')

    private fun post(path: String, payload: JSONObject): JSONObject {
        val req = Request.Builder()
            .url(normalizedUrl() + path)
            .post(payload.toString().toRequestBody(jsonType))
            .header("Accept", "application/json")
            .build()
        http.newCall(req).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) throw IllegalStateException("HTTP ${response.code}: ${body.take(300)}")
            val result = JSONObject(body)
            if (result.has("error")) {
                val error = result.getJSONObject("error")
                val data = error.optJSONObject("data")
                throw IllegalStateException(data?.optString("message") ?: error.optString("message", "Erreur Odoo"))
            }
            return result
        }
    }

    fun login(): String {
        require(normalizedUrl().startsWith("http://") || normalizedUrl().startsWith("https://")) {
            "Saisissez une adresse Odoo commençant par http:// ou https://."
        }
        val payload = JSONObject()
            .put("jsonrpc", "2.0").put("method", "call").put("id", ids.getAndIncrement())
            .put("params", JSONObject()
                .put("db", config.database)
                .put("login", config.username)
                .put("password", config.password))
        val result = post("/web/session/authenticate", payload).optJSONObject("result")
            ?: throw IllegalStateException("Réponse de connexion Odoo invalide")
        val foundUid = result.optInt("uid", 0)
        if (foundUid <= 0) throw IllegalStateException("Identifiants invalides ou base de données incorrecte.")
        uid = foundUid
        return result.optString("name", config.username)
    }

    private fun callKw(model: String, method: String, args: JSONArray, kwargs: JSONObject = JSONObject()): Any? {
        if (uid == null) throw IllegalStateException("Connectez-vous d'abord à Odoo.")
        val payload = JSONObject()
            .put("jsonrpc", "2.0").put("method", "call").put("id", ids.getAndIncrement())
            .put("params", JSONObject()
                .put("model", model).put("method", method).put("args", args).put("kwargs", kwargs))
        return post("/web/dataset/call_kw/$model/$method", payload).opt("result")
    }

    private fun searchRead(model: String, domain: JSONArray, fields: List<String>, limit: Int = 80): JSONArray {
        val kwargs = JSONObject().put("fields", JSONArray(fields)).put("limit", limit).put("order", "id desc")
        return callKw(model, "search_read", JSONArray().put(domain), kwargs) as? JSONArray ?: JSONArray()
    }

    fun searchProducts(query: String): List<OdooProduct> {
        val q = query.trim()
        val domain = if (q.isEmpty()) JSONArray() else JSONArray()
            .put(JSONArray().put("active").put("=").put(true))
            .put("|").put("|")
            .put(JSONArray().put("name").put("ilike").put(q))
            .put(JSONArray().put("default_code").put("ilike").put(q))
            .put(JSONArray().put("barcode").put("ilike").put(q))
        val rows = searchRead(
            "product.product", domain,
            listOf("id", "name", "default_code", "barcode", "lst_price", "product_tmpl_id"), 100
        )
        return (0 until rows.length()).map { i ->
            val o = rows.getJSONObject(i)
            OdooProduct(
                id = o.optInt("id"),
                name = o.optString("name"),
                ref = o.optString("default_code"),
                barcode = o.optString("barcode"),
                salePrice = o.optDouble("lst_price", 0.0),
                templateId = relationId(o.opt("product_tmpl_id"))
            )
        }
    }

    fun updateProduct(product: OdooProduct, name: String, barcode: String, salePrice: Double) {
        require(name.isNotBlank()) { "Le nom du produit est obligatoire." }
        require(salePrice >= 0.0) { "Le prix de vente ne peut pas être négatif." }
        val variantValues = JSONObject().put("name", name.trim()).put("barcode", barcode.trim())
        callKw("product.product", "write", JSONArray().put(JSONArray().put(product.id)).put(variantValues))
        if (product.templateId > 0) {
            callKw("product.template", "write",
                JSONArray().put(JSONArray().put(product.templateId)).put(JSONObject().put("list_price", salePrice)))
        }
    }

    fun searchTransfers(query: String): List<OdooTransfer> {
        val q = query.trim()
        val domain = if (q.isEmpty()) JSONArray() else JSONArray()
            .put(JSONArray().put("name").put("ilike").put(q))
        val rows = searchRead(
            "stock.picking", domain,
            listOf("id", "name", "state", "location_id", "location_dest_id", "scheduled_date"), 100
        )
        return (0 until rows.length()).map { i ->
            val o = rows.getJSONObject(i)
            OdooTransfer(
                id = o.optInt("id"), name = o.optString("name"), state = o.optString("state"),
                source = relationName(o.opt("location_id")), destination = relationName(o.opt("location_dest_id")),
                scheduled = o.optString("scheduled_date")
            )
        }
    }

    fun createInternalTransfer(linesText: String, sourceId: Int, destinationId: Int): String {
        require(sourceId > 0 && destinationId > 0) { "Choisissez les emplacements source et destination." }
        require(sourceId != destinationId) { "La source et la destination doivent être différentes." }
        val lines = linesText.lines().map { it.trim() }.filter { it.isNotEmpty() }
        require(lines.isNotEmpty()) { "Ajoutez au moins une ligne produit." }

        val internalTypes = searchRead(
            "stock.picking.type",
            JSONArray().put(JSONArray().put("code").put("=").put("internal")),
            listOf("id", "name", "default_location_src_id", "default_location_dest_id"), 50
        )
        require(internalTypes.length() > 0) { "Aucun type d'opération interne trouvé dans Odoo." }
        val typeId = internalTypes.getJSONObject(0).optInt("id")
        val moves = JSONArray()
        for (line in lines) {
            val parts = line.split(";").map { it.trim() }
            require(parts.size == 2) { "Format incorrect: $line. Utilisez Référence-ou-code-barres;Quantité." }
            val code = parts[0]
            val qty = parts[1].replace(',', '.').toDoubleOrNull()
                ?: throw IllegalArgumentException("Quantité incorrecte pour $code.")
            require(qty > 0) { "La quantité doit être supérieure à zéro pour $code." }
            val productRows = searchRead(
                "product.product",
                JSONArray().put(JSONArray().put("active").put("=").put(true))
                    .put("|")
                    .put(JSONArray().put("default_code").put("=").put(code))
                    .put(JSONArray().put("barcode").put("=").put(code)),
                listOf("id", "name", "uom_id"), 5
            )
            require(productRows.length() == 1) { "Produit introuvable ou ambigu: $code" }
            val p = productRows.getJSONObject(0)
            val uomId = relationId(p.opt("uom_id"))
            val move = JSONObject()
                .put("name", p.optString("name"))
                .put("product_id", p.optInt("id"))
                .put("product_uom_qty", qty)
                .put("product_uom", uomId)
                .put("location_id", sourceId)
                .put("location_dest_id", destinationId)
            moves.put(JSONArray().put(0).put(0).put(move))
        }
        val pickingVals = JSONObject()
            .put("picking_type_id", typeId)
            .put("location_id", sourceId)
            .put("location_dest_id", destinationId)
            .put("move_ids", moves)
        val createdId = (callKw("stock.picking", "create", JSONArray().put(pickingVals)) as? Number)?.toInt()
            ?: throw IllegalStateException("Odoo n'a pas retourné l'identifiant du transfert créé.")
        val created = searchRead("stock.picking", JSONArray().put(JSONArray().put("id").put("=").put(createdId)), listOf("name"), 1)
        return if (created.length() > 0) created.getJSONObject(0).optString("name") else "ID $createdId"
    }

    fun listLocations(): List<Pair<Int, String>> {
        val rows = searchRead(
            "stock.location",
            JSONArray().put(JSONArray().put("usage").put("in").put(JSONArray().put("internal").put("transit"))),
            listOf("id", "complete_name"), 300
        )
        return (0 until rows.length()).map { i ->
            val o = rows.getJSONObject(i)
            o.optInt("id") to o.optString("complete_name")
        }
    }

    fun downloadPickingPdf(pickingId: Int, reportName: String = "stock.report_picking"): ByteArray {
        val request = Request.Builder().url("${normalizedUrl()}/report/pdf/$reportName/$pickingId").get().build()
        http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IllegalStateException("Impossible de récupérer le PDF (HTTP ${response.code}).")
            val bytes = response.body?.bytes() ?: ByteArray(0)
            if (bytes.isEmpty() || bytes.size < 5 || String(bytes.copyOfRange(0, 5)) != "%PDF-") {
                throw IllegalStateException("Odoo n'a pas retourné un PDF. Vérifiez le nom du rapport.")
            }
            return bytes
        }
    }

    private fun relationId(value: Any?): Int = if (value is JSONArray && value.length() > 0) value.optInt(0) else 0
    private fun relationName(value: Any?): String = if (value is JSONArray && value.length() > 1) value.optString(1) else ""
}
