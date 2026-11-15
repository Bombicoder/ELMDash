package fr.elmdash.core

import android.content.Context
import com.google.android.gms.wearable.PutDataMapRequest
import com.google.android.gms.wearable.Wearable
import org.json.JSONArray
import org.json.JSONObject

/** Liaison téléphone ⇄ montre (Data Layer). Config = DataItem ; mesures et codes défaut = messages. */
object WearSync {
    fun pushConfig(ctx: Context, store: ConfigStore) {
        val req = PutDataMapRequest.create("/config").apply {
            dataMap.putString("json", store.configJson()); dataMap.putLong("ts", System.currentTimeMillis())
        }.asPutDataRequest().setUrgent()
        Wearable.getDataClient(ctx).putDataItem(req)
    }

    fun pushValues(ctx: Context, values: Map<String, Double>) {
        val o = JSONObject(); values.forEach { (k, v) -> if (v.isFinite()) o.put(k, v) }
        send(ctx, "/telemetry", o.toString())
    }

    fun pushDtc(ctx: Context, mil: Boolean, codes: List<String>) =
        send(ctx, "/dtc", JSONObject().put("mil", mil).put("codes", JSONArray(codes)).toString())

    fun sendVisible(ctx: Context, ids: Collection<String>) = send(ctx, "/visible", JSONArray(ids.toList()).toString())
    fun sendDtcRequest(ctx: Context, req: String) = send(ctx, "/dtcreq", req)
    fun hello(ctx: Context) = send(ctx, "/hello", "")

    private fun send(ctx: Context, path: String, payload: String) {
        val mc = Wearable.getMessageClient(ctx)
        Wearable.getNodeClient(ctx).connectedNodes.addOnSuccessListener { nodes ->
            nodes.forEach { mc.sendMessage(it.id, path, payload.toByteArray()) }
        }
    }
}
