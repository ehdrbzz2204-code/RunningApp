package com.example.myapplication

import android.content.Context
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.net.URLEncoder
import kotlin.math.roundToInt

internal data class RunnerData(
    val completed: Int, val week: JSONObject, val month: JSONObject,
    val goal: JSONObject?, val missions: List<MissionRecommendation>, val history: List<JSONObject>
)

internal fun km(meters: Int) = String.format(Locale.ROOT, "%.2f", meters / 1000.0)
private fun dateText(pattern: String, zone: String) = SimpleDateFormat(pattern,Locale.ROOT).apply {
    timeZone = TimeZone.getTimeZone(zone)
}.format(Date())

internal class RunnerRepository(context: Context, private val session: SavedSession) {
    private val api = AuthApiClient()
    private val queue = context.getSharedPreferences("run-outbox-" + session.userId, Context.MODE_PRIVATE)
    private val cache = context.getSharedPreferences("runner-dashboard-" + session.userId, Context.MODE_PRIVATE)
    fun cached(): RunnerData? = cache.getString("dashboard",null)?.let { raw ->
        try { decode(JSONObject(raw)) } catch (_: org.json.JSONException) { null }
    }
    private fun decode(json:JSONObject):RunnerData {
        val offers=json.getJSONArray("missions")
        val history=json.getJSONArray("history")
        return RunnerData(json.getInt("completed"),json.getJSONObject("week"),json.getJSONObject("month"),
            json.optJSONObject("goal"), List(offers.length()){i->offers.getJSONObject(i).let{
                MissionRecommendation(it.getString("title"),it.getString("description"),it.getString("id"))
            }}, List(history.length()){i->history.getJSONObject(i)})
    }
    private val token = session.accessToken
    fun enqueue(run: RunTrackingState, missionId: String?, assessment: Boolean) {
        val json = JSONObject().put("clientRunId", run.clientRunId)
            .put("distanceMeters",run.distanceMeters.roundToInt()).put("movingSeconds",(run.elapsedMs/1000).coerceAtLeast(1))
            .put("finishedAt",dateText("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'","UTC"))
            .put("type",if(missionId!=null) "mission" else if(assessment) "assessment" else "free")
        if(missionId!=null) json.put("missionId",missionId)
        check(queue.edit().putString(run.clientRunId,json.toString()).commit()) { "기기에 기록을 저장하지 못했어요." }
    }
    suspend fun upload() {
        for ((id, raw) in queue.all.entries.sortedBy { JSONObject(it.value as String).getString("finishedAt") }) {
            api.accountJson(token,"/runs","POST",JSONObject(raw as String))
            queue.edit().remove(id).apply()
        }
    }
    fun pending() = queue.all.size
    suspend fun start(id:String) = api.accountJson(token,"/missions/start","POST",JSONObject().put("missionId",id)).getJSONObject("mission")
    suspend fun goal(distance:Boolean,value:Int) {
        val json=JSONObject().put("type",if(distance)"distance" else "pace")
            .put(if(distance)"targetDistanceMeters" else "targetPaceSecondsPerKm",value)
        api.accountJson(token,"/goals/active","PUT",json)
    }
    suspend fun load(): RunnerData {
        val assessment=api.accountJson(token,"/assessment")
        val goal=api.accountJson(token,"/goals/active").optJSONObject("goal")
        val week=api.accountJson(token,"/stats?period=week")
        val month=api.accountJson(token,"/stats?period=month")
        val offers=api.accountJson(token,"/missions/recommend","POST",JSONObject().put("date",dateText("yyyy-MM-dd","Asia/Seoul"))).optJSONArray("missions")
        val history=mutableListOf<JSONObject>()
        var cursor:String?=null
        do {
            val page=api.accountJson(token,"/runs?limit=100"+(cursor?.let{"&cursor="+URLEncoder.encode(it,"UTF-8")} ?: ""))
            val items=page.getJSONArray("items")
            repeat(items.length()){ history.add(items.getJSONObject(it)) }
            cursor=if(page.isNull("nextCursor")) null else page.getString("nextCursor")
        } while(cursor!=null)
        val json=JSONObject().put("completed",assessment.getInt("completedRuns")).put("week",week)
            .put("month",month).put("goal",goal).put("missions",offers ?: org.json.JSONArray())
            .put("history",org.json.JSONArray(history))
        val result=decode(json)
        cache.edit().putString("dashboard",json.toString()).apply()
        return result
    }
}




internal fun parseMission(json:JSONObject) = com.example.myapplication.mission.Mission(
    json.getString("id"),json.getString("date"),json.getString("type"),json.getString("title"),
    json.getString("description"),json.getInt("targetDistanceMeters"),json.getInt("maxPaceSecondsPerKm"),
    if(json.isNull("minPaceSecondsPerKm")) null else json.getInt("minPaceSecondsPerKm"),
    json.getInt("recommendedPaceSecondsPerKm")
)
