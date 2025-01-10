package de.qrdn.phy2music

import android.content.Intent
import android.os.Bundle
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat

import android.util.Log
import android.widget.Button
import android.widget.TextView
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import com.android.volley.RequestQueue
import com.android.volley.toolbox.JsonObjectRequest
import com.android.volley.toolbox.Volley
import com.spotify.android.appremote.api.ConnectionParams
import com.spotify.android.appremote.api.Connector
import com.spotify.android.appremote.api.SpotifyAppRemote

import com.spotify.protocol.types.Track
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

// https://github.com/spotify/android-sdk/blob/master/app-remote-lib/README.md
// https://developer.spotify.com/documentation/android/tutorials/getting-started#introduction

class MainActivity : AppCompatActivity() {

    private val log_tag = "phy2music_main"

    private val clientId = "983759c0-665e-4a65-b5a5-75ef353cc668"
    private val redirectUri = "https://phy2music.qrdn.de/spotify-callback"
    private var spotifyAppRemote: SpotifyAppRemote? = null

    // logging

    private var logView: TextView? = null

    private fun currentTimeString(): String? {
        return LocalDateTime.now().format(DateTimeFormatter.ISO_DATE_TIME)
    }

    private fun log_e(msg: String, exc: Throwable? = null) {
        Log.e(log_tag, msg, exc)
        logView?.append(currentTimeString() + " E $msg\n")
    }

    private fun log_i(msg: String) {
        Log.i(log_tag, msg)
        logView?.append(currentTimeString() + " I $msg\n")
    }

    private fun log_d(msg: String) {
        Log.d(log_tag, msg)
        logView?.append(currentTimeString() + " D $msg\n")
    }

    // GUI, spotify

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_main)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        logView = findViewById(R.id.logView)
        logView?.text = currentTimeString() + " MainActivity onCreate"

        findViewById<Button>(R.id.scanToPlayButton).setOnClickListener {
            doScanAndPlay()
        }

        // initialize barcode scanner connection, see below
        resultLauncher = registerForActivityResult(
            ActivityResultContracts.StartActivityForResult()
        ) { result ->
            if (result.resultCode == RESULT_OK) {
                val content = result.data?.getStringExtra("SCAN_RESULT")
                log_d("Scan result: $content")
                if (! content.isNullOrEmpty()) {
                    handleScanResult(content)
                }
            }
        }
        // initialize HTTP API client
        requestQueue = Volley.newRequestQueue(this)
    }

    override fun onStart() {
        super.onStart()
        val connectionParams = ConnectionParams.Builder(clientId)
            .setRedirectUri(redirectUri)
            .showAuthView(true)
            .build()

        SpotifyAppRemote.connect(this, connectionParams, object : Connector.ConnectionListener {
            override fun onConnected(appRemote: SpotifyAppRemote) {
                spotifyAppRemote = appRemote
                log_d("Connected to Spotify app")
                connected()
            }

            override fun onFailure(throwable: Throwable) {
                log_e("Failed to connect to Spotify app: ${throwable.message}", throwable)
            }
        })
    }

    private fun connected() {
        spotifyAppRemote?.let { appRemote ->
            // Subscribe to PlayerState
            appRemote.playerApi.subscribeToPlayerState().setEventCallback { event ->
                val track: Track = event.track
                val state: String = if (event.isPaused) "paused" else "playing"
                log_d("$state track: ${track.name} by ${track.artist.name}")
            }
        }

    }

    override fun onStop() {
        super.onStop()
        log_d("Closing & disconnect from spotify app")
        spotifyAppRemote?.let {
            SpotifyAppRemote.disconnect(it)
        }
    }

    private fun doPlay(spotifyURI: String) {
        spotifyAppRemote?.playerApi?.play(spotifyURI)?.setResultCallback {
            log_d("Spotify success play($spotifyURI)")
        }?.setErrorCallback {
            log_e("Spotify failed play($spotifyURI)")
        }
    }

    // barcode/QR-code scanning & code lookup in web APIs

    private lateinit var requestQueue: RequestQueue
    private lateinit var resultLauncher: ActivityResultLauncher<Intent>

    private fun doScanAndPlay() {
        // barcode scanning: https://github.com/markusfisch/BinaryEye#scan-intent
        resultLauncher.launch(Intent("com.google.zxing.client.android.SCAN"))
    }

    /*
     * UPC-A: 12 digits
     * EAN-13: 13 digits (superset of UPC-A)
     */
    private val BARCODE_REGEX_EAN = Regex("^\\d{12}\\d?$")

    private fun handleScanResult(scanResult: String) {
        if (scanResult.startsWith("spotify:")) { // spotify URI
            doPlay(scanResult)
        } else if (BARCODE_REGEX_EAN.matches(scanResult)) {
            log_i("Scanned EAN/UPC $scanResult")
            // get from EAN/UPC to spotify URI:
            // run our variants in parallel, requestQueue.stop(tag=scanResult) once one is finished
            lookupCodeAtSpotify(scanResult)
            lookupCodeViaMusicBrainz(scanResult)
        } else {
            log_e("Scan Result unknown: $scanResult")
        }
        // TODO: special URL in QR-code for speaker selection (spotify connect / bluetooth device)
    }

    private fun lookupCodeAtSpotify(scanResult: String) {
        // probably few hits
        requestQueue.add(JsonObjectRequest(
            "https://api.spotify.com/v1/search?q=upc%3A$scanResult&type=album",
            {spResponse ->
                val spRespAlb = spResponse.optJSONObject("albums")
                log_d("Found ${spRespAlb?.optInt("total")} spotify albums for upc=$scanResult")
                if (spRespAlb != null && spRespAlb.optInt("total") > 0) {
                    for (i in 0..<spRespAlb.optInt("total", 0)) {
                        val spAlbumURI = spRespAlb.optJSONArray("items")?.
                        optJSONObject(i)?.optString("uri")
                        if (spAlbumURI != null) {
                            log_d("Found spotify URI $spAlbumURI for upc=$scanResult")
                            doPlay(spAlbumURI)
                            requestQueue.cancelAll(scanResult)
                            break
                        }
                    }
                }
            },
            {
                log_e("Failed spotify lookup of upc=$scanResult: ${it.message}", it.cause)
            }
        ).setTag(scanResult))
    }

    private fun urlParamEnc(value: String?): String {
        return URLEncoder.encode(value, StandardCharsets.UTF_8.name())
    }

    private fun lookupCodeViaMusicBrainz(scanResult: String) {
        // query musicbrainz for release.barcode field ...
        requestQueue.add( JsonObjectRequest(
            // without API key rate limited to 1/s
            "https://musicbrainz.org/ws/2/release/?fmt=json&query=barcode%3A$scanResult",
            {response ->
                val mbResult = response.optJSONObject("releases")
                if (mbResult != null) {
                    log_d("Found ${mbResult.length()} musicbrainz releases for $scanResult")
                    for (relKey in mbResult.keys() ) {
                        val mbRelease = mbResult.optJSONObject(relKey) ?: continue
                        var mbRelTitle = mbRelease.optString("title")
                        val mbRelArtist = mbRelease.optJSONObject("artist-credit")?.optJSONObject("0")?.optJSONObject("artist")?.optString("name")

                        // workaround for mb having the artist as title prefix, but spotify doesn't
                        // e.g. https://musicbrainz.org/release/3d0d7f46-7fac-45f7-8a30-521f98c2d709
                        if (!mbRelTitle.isNullOrEmpty() &&
                            !mbRelArtist.isNullOrEmpty() &&
                            mbRelTitle.startsWith(mbRelArtist) &&
                            (mbRelTitle.length > mbRelArtist.length)) {
                            mbRelTitle = mbRelTitle.removePrefix(mbRelArtist).trimStart()
                        }

                        // ... and use the releases title and artist to query spotify
                        requestQueue.add(JsonObjectRequest(
                            "https://api.spotify.com/v1/search?q=artist%3A${urlParamEnc(mbRelArtist)}+album%3A${urlParamEnc(mbRelTitle)}&type=album",
                            {spResponse ->
                                val spRespAlb = spResponse.optJSONObject("albums")
                                log_d("Found ${spRespAlb?.optInt("total")} spotify albums from musicbrainz' release details for $scanResult")
                                if (spRespAlb != null && spRespAlb.optInt("total") > 0) {
                                    for (i in 0..<spRespAlb.optInt("total", 0)) {
                                        val spAlbumURI = spRespAlb.optJSONArray("items")?.
                                            optJSONObject(i)?.optString("uri")
                                        if (spAlbumURI != null) {
                                            log_d("Found spotify URI $spAlbumURI from musicbrainz details for $scanResult")
                                            doPlay(spAlbumURI)
                                            requestQueue.cancelAll(scanResult)
                                            break
                                        }
                                    }
                                }
                            },
                            {
                                log_e("Failed spotify lookup of musicbrainz' release $mbRelTitle by $mbRelArtist: ${it.message}", it.cause)
                            }
                        ).setTag(scanResult))
                    }
                }
            },
            {
                log_e("Failed musicbrainz lookup of barcode $scanResult: ${it.message}", it.cause)
            }).setTag(scanResult)
        )
    }


}