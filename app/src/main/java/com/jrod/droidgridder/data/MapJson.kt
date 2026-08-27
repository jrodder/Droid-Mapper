package com.jrod.droidgridder.data

import com.jrod.droidgridder.model.MapFile
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

private val json = Json { prettyPrint = true; ignoreUnknownKeys = true; encodeDefaults = true }

fun encodeMap(map: MapFile): String = json.encodeToString(map)
fun decodeMap(text: String): MapFile = json.decodeFromString(text)
