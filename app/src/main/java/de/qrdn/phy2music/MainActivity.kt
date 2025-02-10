package de.qrdn.phy2music

import android.content.Intent
import android.os.Bundle
import android.text.method.ScrollingMovementMethod
import android.util.Log
import android.widget.Button
import android.widget.TextView
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.android.volley.RequestQueue
import com.android.volley.Response
import com.android.volley.Response.Listener
import com.android.volley.toolbox.JsonObjectRequest
import com.android.volley.toolbox.Volley
import com.spotify.android.appremote.api.ConnectionParams
import com.spotify.android.appremote.api.Connector
import com.spotify.android.appremote.api.SpotifyAppRemote
import com.spotify.protocol.types.Track
import org.json.JSONObject
import java.net.URLEncoder
import java.nio.charset.Charset
import java.nio.charset.StandardCharsets
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Timer
import kotlin.concurrent.schedule


// https://github.com/spotify/android-sdk/blob/master/app-remote-lib/README.md
// https://developer.spotify.com/documentation/android/tutorials/getting-started#introduction

class MainActivity : AppCompatActivity() {

    private val log_tag = "phy2music_main"

    private val clientId = "cb7b34894da64b8f99322839f25db04f"
    private val clientSecret = "..."
    private val redirectUri = "https://qrdn.de/phy2music-spotify-callback"
    private var spotifyAppRemote: SpotifyAppRemote? = null

    private val connectionParams = ConnectionParams.Builder(clientId)
        .setRedirectUri(redirectUri)
        .showAuthView(true)
        .build()

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
        logView?.movementMethod = ScrollingMovementMethod()
        logView?.text = currentTimeString() + " MainActivity onCreate\n"

        findViewById<Button>(R.id.scanToPlayButton).setOnClickListener {
            // barcode scanning: https://github.com/markusfisch/BinaryEye#scan-intent
            resultLauncher.launch(Intent("com.google.zxing.client.android.SCAN"))
        }
        findViewById<Button>(R.id.playPauseButton).setOnClickListener {
            val player = spotifyAppRemote?.playerApi
            if (player == null)
                log_e("Can't toggle play/pause: player null, spotifyAppRemote: $spotifyAppRemote")
            else {
                player.playerState?.setResultCallback {
                    if (it.isPaused)
                        player.resume()
                    else
                        player.pause()
                }
            }
        }

        // initialize barcode scanner connection, see below
        resultLauncher = registerForActivityResult(
            ActivityResultContracts.StartActivityForResult()
        ) { result ->
            if (result.resultCode == RESULT_OK) {
                val content = result.data?.getStringExtra("SCAN_RESULT")
                log_d("Scan result: $content")
                if (! content.isNullOrEmpty()) {
                    // NOTE: onActivityResult() is called before onRestart/onStart, which normally initialize the spotify connection
                    // https://stackoverflow.com/a/15214232
                    connectToSpotify(object: Connector.ConnectionListener {
                        override fun onConnected(p0: SpotifyAppRemote?) {
                            // ... and only trigger doPlay after connection established
                            handleScanResult(content)
                        }
                        override fun onFailure(p0: Throwable?) {}
                    })
                }
            }
        }
        // initialize HTTP API client
        requestQueue = Volley.newRequestQueue(this)

        SpotifyAppRemote.setDebugMode(true)
    }

    override fun onStart() {
        super.onStart()
        connectToSpotify(null)
    }

    private fun connectToSpotify(listener: Connector.ConnectionListener?) {
        SpotifyAppRemote.connect(this, connectionParams, object : Connector.ConnectionListener {
            override fun onConnected(appRemote: SpotifyAppRemote) {
                spotifyAppRemote = appRemote
                log_d("Connected to Spotify app")
                spotifyAppRemote?.let { ar ->
                    // Subscribe to PlayerState
                    ar.playerApi.subscribeToPlayerState().setEventCallback { event ->
                        val track: Track = event.track
                        val state: String = if (event.isPaused) "paused" else "playing"
                        log_d("$state track: ${track.name} by ${track.artist.name}")
                    }

                    /* TODO: get web API token from user (instead of using the static one from app developer)
                    AuthorizationClient.openLoginActivity(
                        this,
                        SPOTIFY_AUTH_REQUEST_CODE, // Assign any unique integer value
                        authRequest
                    )
                    */
                }
                listener?.onConnected(appRemote)
            }

            override fun onFailure(throwable: Throwable) {
                log_e("Failed to connect to Spotify app: ${throwable.message}", throwable)
                listener?.onFailure(throwable)
            }
        })
    }

    override fun onStop() {
        super.onStop()
        log_d("Closing & disconnect from spotify app")
        spotifyAppRemote?.let {
            SpotifyAppRemote.disconnect(it)
        }
        spotifyAppRemote = null  // old object will error on play() without telling why
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
        doAuthedSpotifyRequest(
            "search?q=upc%3A$scanResult&type=album",
            tag = scanResult,
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
        )
    }

    private fun urlParamEnc(value: String?): String {
        return URLEncoder.encode(value, StandardCharsets.UTF_8.name())
    }

    private fun parseMusicBrainzSearchResult(mbResponse: JSONObject): Triple<String, String, String>? {
        val mbReleases = mbResponse.optJSONArray("releases")
        if (mbReleases == null) {
            return null
        }
        for (i in 0..<  mbReleases.length()) {
            val mbRelease = mbReleases.getJSONObject(i) ?: continue
            val mbReleaseId = mbRelease.optString("id")
            var mbRelTitle = mbRelease.optString("title")
            val mbRelArtist = mbRelease.optJSONArray("artist-credit")
                ?.getJSONObject(0)  // TODO: check all returned artists
                ?.optJSONObject("artist")
                ?.optString("name")

            // workaround for mb having the artist as title prefix, but spotify doesn't
            // e.g. https://musicbrainz.org/release/3d0d7f46-7fac-45f7-8a30-521f98c2d709
            if (!mbRelTitle.isNullOrEmpty() &&
                !mbRelArtist.isNullOrEmpty() &&
                mbRelTitle.startsWith(mbRelArtist) &&
                (mbRelTitle.length > mbRelArtist.length)
            ) {
                mbRelTitle = mbRelTitle.removePrefix(mbRelArtist).trimStart()
            }
            return Triple(mbRelTitle, mbRelArtist?: "", mbReleaseId)
        }
        return null
    }

    private fun lookupCodeViaMusicBrainz(scanResult: String) {
        // query musicbrainz for release.barcode field ...
        requestQueue.add( JsonObjectRequest(
            // without API key rate limited to 1/s
            "https://musicbrainz.org/ws/2/release/?fmt=json&query=barcode%3A$scanResult",
            {response ->
                log_d("Found ${response?.optInt("count")} musicbrainz releases for $scanResult")
                // ... and use the releases title and artist to query spotify
                val mbRes = parseMusicBrainzSearchResult(response)
                if (mbRes == null) {
                    log_i("Failed to get album+artist from mb response for $scanResult: $response")
                } else {
                    log_d("Found musicbrainz release ${mbRes.third} for $scanResult")
                    lookupAlbumAtSpotify(mbRes.first, mbRes.second, scanResult)
                }
            },
            {
                log_e("Failed musicbrainz lookup of barcode $scanResult: ${it.message}", it.cause)
            }).setTag(scanResult)
        )
    }

    private fun lookupAlbumAtSpotify(releaseTitle: String, artist: String, scanResult: String) {
        log_d("Query spotify for $releaseTitle by $artist (barcode $scanResult)")
        doAuthedSpotifyRequest(
            "/search?q=artist%3A${urlParamEnc(artist)}+album%3A${urlParamEnc(releaseTitle)}&type=album",
            tag = scanResult,
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
                log_e("Failed spotify lookup of musicbrainz' release $releaseTitle by $artist: ${it.message}", it.cause)
            }
        )
    }

    private var spotifyAccessToken: String? = null
    private fun doAuthedSpotifyRequest(
        urlPath: String,
        tag: String,
        listener: Listener<JSONObject>,
        errorListener: Response.ErrorListener?
    )
    {
        if (spotifyAccessToken.isNullOrEmpty()) {
            requestQueue.add(object : JsonObjectRequest(
                Method.POST,
                "https://accounts.spotify.com/api/token",
                // JsonObjectRequest can only send json body, below we override getBody() to return form-urlencoded needed for spotify
                // must pass a non-null value so it uses method=POST
                JSONObject(),
                {
                    spotifyAccessToken = it.optString("access_token")
                    log_d("Got new spotify token valid for ${it.optInt("expires_in")}: ${it.optString("access_token").subSequence(0, 12)}...")
                    doAuthedSpotifyRequestInternal(urlPath, tag, listener, errorListener)

                    // unset token when it gets invalid, so the code above will fetch a new one
                    Timer().schedule(it.optInt("expires_in", 600) * 1000L) {
                        log_d("Spotify access token lifetime expired, nulling")
                        spotifyAccessToken = null
                    }
                },
                {
                    log_e("Failed to get spotify token: ${it.message}", it.cause)
                }
            ) {
                override fun getHeaders(): Map<String, String> {
                    return mapOf(Pair("Content-Type", "application/x-www-form-urlencoded"))
                }

                override fun getBody(): ByteArray {
                    val urlEncoded = "grant_type=client_credentials&client_id=$clientId&client_secret=$clientSecret"
                    return urlEncoded.toByteArray(Charset.forName(PROTOCOL_CHARSET))
                }

                override fun getBodyContentType(): String {
                    return "application/x-www-form-urlencoded"
                }
            })
        } else {
            doAuthedSpotifyRequestInternal(urlPath, tag, listener, errorListener)
        }

    }

    private fun doAuthedSpotifyRequestInternal(urlPath: String,
                                               tag: String,
                                               listener: Listener<JSONObject>,
                                               errorListener: Response.ErrorListener?) {
        val req = object: JsonObjectRequest(
            "https://api.spotify.com/v1/$urlPath",
            listener,
            errorListener
        ) {
            override fun getHeaders(): Map<String, String> {
                return mapOf(Pair("Authorization", "Bearer $spotifyAccessToken"))
            }
        }
        req.setTag(tag)
        requestQueue.add(req)
    }
}