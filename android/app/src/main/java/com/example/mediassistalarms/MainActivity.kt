package com.example.mediassistalarms

import android.content.Context
import android.content.Intent
import android.media.RingtoneManager
import android.os.Bundle
import android.provider.AlarmClock
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL

data class MedicationAlarm(
    val id: Long,
    val medicineName: String,
    val dosageInstruction: String,
    val reminderTime: String,
    val hour: Int,
    val minute: Int,
    val formattedTime12h: String,
    val status: String
)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val deepLinkPhone = intent?.data?.getQueryParameter("phone") ?: "+919876543210"

        setContent {
            MaterialTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = Color(0xFF0F172A)
                ) {
                    MediAssistAlarmScreen(initialPhone = deepLinkPhone)
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MediAssistAlarmScreen(initialPhone: String) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    var phone by remember { mutableStateOf(initialPhone) }
    var alarms by remember { mutableStateOf<List<MedicationAlarm>>(emptyList()) }
    var isLoading by remember { mutableStateOf(false) }
    var statusMessage by remember { mutableStateOf("") }

    fun fetchAlarms() {
        coroutineScope.launch {
            isLoading = true
            statusMessage = "Fetching alarms from MediAssist..."
            try {
                val fetched = withContext(Dispatchers.IO) {
                    val url = URL("https://mediassist-1hdl.onrender.com/api/v1/reminders/alarms-data?phone=" + java.net.URLEncoder.encode(phone, "UTF-8"))
                    val conn = url.openConnection() as HttpURLConnection
                    conn.connectTimeout = 8000
                    conn.readTimeout = 8000
                    conn.requestMethod = "GET"

                    if (conn.responseCode == 200) {
                        val reader = BufferedReader(InputStreamReader(conn.inputStream))
                        val response = reader.readText()
                        reader.close()

                        val json = JSONObject(response)
                        val array = json.optJSONArray("alarms") ?: org.json.JSONArray()
                        val list = mutableListOf<MedicationAlarm>()
                        for (i in 0 until array.length()) {
                            val item = array.getJSONObject(i)
                            list.add(
                                MedicationAlarm(
                                    id = item.optLong("id"),
                                    medicineName = item.optString("medicineName", "Medicine"),
                                    dosageInstruction = item.optString("dosageInstruction", "As prescribed"),
                                    reminderTime = item.optString("reminderTime", "08:00"),
                                    hour = item.optInt("hour", 8),
                                    minute = item.optInt("minute", 0),
                                    formattedTime12h = item.optString("formattedTime12h", "08:00 AM"),
                                    status = item.optString("status", "PENDING")
                                )
                            )
                        }
                        list
                    } else {
                        emptyList()
                    }
                }
                alarms = fetched
                statusMessage = if (fetched.isEmpty()) "No active alarms found. Upload prescription on WhatsApp!" else "Loaded ${fetched.size} prescription alarms."
            } catch (e: Exception) {
                statusMessage = "Could not connect to server: ${e.localizedMessage}"
            } finally {
                isLoading = false
            }
        }
    }

    LaunchedEffect(Unit) {
        fetchAlarms()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(16.dp)
    ) {
        // App Header
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp)
        ) {
            Text(text = "💊", fontSize = 28.sp)
            Spacer(modifier = Modifier.width(8.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "MediAssist Alarms",
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    fontSize = 20.sp
                )
                Text(
                    text = "Hardware Alarm Clock Companion",
                    color = Color(0xFF94A3B8),
                    fontSize = 12.sp
                )
            }
        }

        // Phone Input Card
        Card(
            shape = RoundedCornerShape(12.dp),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF1E293B)),
            modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp)
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                Text(text = "Patient WhatsApp Number", color = Color(0xFF94A3B8), fontSize = 12.sp)
                Spacer(modifier = Modifier.height(4.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = phone,
                        onValueChange = { phone = it },
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = Color.White,
                            unfocusedTextColor = Color.White,
                            focusedBorderColor = Color(0xFF0284C7),
                            unfocusedBorderColor = Color(0xFF334155)
                        )
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Button(
                        onClick = { fetchAlarms() },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF0284C7)),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Text("Sync")
                    }
                }
            }
        }

        // Status banner
        if (statusMessage.isNotBlank()) {
            Text(
                text = statusMessage,
                color = Color(0xFF38BDF8),
                fontSize = 12.sp,
                modifier = Modifier.padding(bottom = 8.dp)
            )
        }

        // Hero 1-Tap Hardware Clock Sync Button
        Button(
            onClick = {
                if (alarms.isEmpty()) {
                    Toast.makeText(context, "No alarms to sync. Fetch alarms first.", Toast.LENGTH_SHORT).show()
                } else {
                    var successCount = 0
                    for (a in alarms) {
                        val ok = setHardwareAlarm(a.medicineName, a.hour, a.minute, a.dosageInstruction, context)
                        if (ok) successCount++
                    }
                    Toast.makeText(context, "⏰ Successfully set $successCount alarm(s) in Phone Clock!", Toast.LENGTH_LONG).show()
                }
            },
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF10B981)),
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier.fillMaxWidth().height(52.dp).padding(bottom = 12.dp)
        ) {
            Text(
                text = "⚡ SYNC ALL TO PHONE CLOCK",
                fontWeight = FontWeight.Bold,
                fontSize = 15.sp,
                color = Color.White
            )
        }

        // Alarm List
        LazyColumn(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            items(alarms) { alarm ->
                AlarmCard(
                    alarm = alarm,
                    onSetSingle = {
                        val ok = setHardwareAlarm(alarm.medicineName, alarm.hour, alarm.minute, alarm.dosageInstruction, context)
                        if (ok) {
                            Toast.makeText(context, "⏰ Set alarm for ${alarm.medicineName} at ${alarm.formattedTime12h}", Toast.LENGTH_SHORT).show()
                        } else {
                            Toast.makeText(context, "Could not launch Clock app.", Toast.LENGTH_SHORT).show()
                        }
                    }
                )
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        // Test Ringtone Button
        OutlinedButton(
            onClick = {
                try {
                    val alertUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
                        ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
                    val r = RingtoneManager.getRingtone(context, alertUri)
                    r.play()
                    Toast.makeText(context, "🔊 Playing phone alarm ringtone...", Toast.LENGTH_SHORT).show()
                } catch (e: Exception) {
                    Toast.makeText(context, "Audio error: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            },
            shape = RoundedCornerShape(10.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("🔊 Test Phone Alarm Ringtone", color = Color(0xFFCBD5E1))
        }
    }
}

@Composable
fun AlarmCard(
    alarm: MedicationAlarm,
    onSetSingle: () -> Unit
) {
    Card(
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF1E293B)),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = alarm.medicineName,
                        fontWeight = FontWeight.Bold,
                        color = Color.White,
                        fontSize = 16.sp
                    )
                    Text(
                        text = alarm.dosageInstruction,
                        color = Color(0xFF94A3B8),
                        fontSize = 13.sp
                    )
                }
                Surface(
                    color = Color(0xFF0F172A),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text(
                        text = alarm.formattedTime12h,
                        color = Color(0xFF38BDF8),
                        fontWeight = FontWeight.Bold,
                        fontSize = 14.sp,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            Button(
                onClick = onSetSingle,
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF334155)),
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("⏰ Set in Phone Clock", color = Color.White, fontSize = 13.sp)
            }
        }
    }
}

fun setHardwareAlarm(medicineName: String, hour: Int, minute: Int, dosage: String, context: Context): Boolean {
    return try {
        val intent = Intent(AlarmClock.ACTION_SET_ALARM).apply {
            putExtra(AlarmClock.EXTRA_MESSAGE, "$medicineName ($dosage)")
            putExtra(AlarmClock.EXTRA_HOUR, hour)
            putExtra(AlarmClock.EXTRA_MINUTES, minute)
            putExtra(AlarmClock.EXTRA_SKIP_UI, true)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        context.startActivity(intent)
        true
    } catch (e: Exception) {
        false
    }
}
