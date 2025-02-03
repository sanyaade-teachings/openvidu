package io.openvidu.test.e2e;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.KeyManagementException;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

import org.apache.commons.io.FileUtils;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.containers.wait.strategy.WaitStrategy;
import org.testcontainers.utility.DockerImageName;

import io.livekit.server.IngressServiceClient;
import io.livekit.server.RoomServiceClient;
import io.openvidu.test.browsers.BrowserUser;
import io.openvidu.test.browsers.ChromeUser;
import io.openvidu.test.browsers.EdgeUser;
import io.openvidu.test.browsers.FirefoxUser;
import io.openvidu.test.browsers.utils.BrowserNames;
import io.openvidu.test.browsers.utils.CommandLineExecutor;
import livekit.LivekitIngress.IngressInfo;
import livekit.LivekitModels.Room;
import okhttp3.OkHttpClient;
import retrofit2.Response;

public class OpenViduTestE2e {

	private final static WaitStrategy waitBrowser = Wait.forLogMessage("^.*Started Selenium Standalone.*$", 1);

	protected static String RTSP_SERVER_IMAGE = "lroktu/vlc-server:latest";
	protected static String SRT_SERVER_IMAGE = "linuxserver/ffmpeg:latest";
	protected static int RTSP_SRT_PORT = 8554;

	protected static String LIVEKIT_API_KEY = "devkey";
	protected static String LIVEKIT_API_SECRET = "secret";
	protected static String LIVEKIT_URL = "ws://localhost:7880/";
	protected static String APP_URL = "https://localhost:4200/";

	protected static String OPENVIDU_PRO_LICENSE = "not_valid";
	protected static String OPENVIDU_PRO_LICENSE_API = "not_valid";

	// https://hub.docker.com/r/selenium/standalone-chrome/tags
	protected static String CHROME_VERSION = "latest";
	// https://hub.docker.com/r/selenium/standalone-firefox/tags
	protected static String FIREFOX_VERSION = "latest";
	// https://hub.docker.com/r/selenium/standalone-edge/tags
	protected static String EDGE_VERSION = "latest";

	protected static Exception ex = null;
	protected final Object lock = new Object();

	protected static final Logger log = LoggerFactory.getLogger(OpenViduTestE2e.class);
	protected static final CommandLineExecutor commandLine = new CommandLineExecutor();
	protected static final String RECORDING_IMAGE = "openvidu/openvidu-recording";

	protected Collection<BrowserUser> browserUsers = new HashSet<>();
	protected static Collection<GenericContainer<?>> containers = new HashSet<>();

	protected static RoomServiceClient LK;
	protected static IngressServiceClient LK_INGRESS;

	protected static void checkFfmpegInstallation() {
		String ffmpegOutput = commandLine.executeCommand("which ffmpeg", 60);
		if (ffmpegOutput == null || ffmpegOutput.isEmpty()) {
			log.error("ffmpeg package is not installed in the host machine");
			Assertions.fail();
			return;
		} else {
			log.info("ffmpeg is installed and accesible");
		}
	}

	private GenericContainer<?> chromeContainer(String image, long shmSize, int maxBrowserSessions, boolean headless) {
		Map<String, String> map = new HashMap<>();
		map.put("SE_OPTS", "--port 4444");
		if (headless) {
			map.put("START_XVFB", "false");
		}
		if (maxBrowserSessions > 1) {
			map.put("SE_NODE_OVERRIDE_MAX_SESSIONS", "true");
			map.put("SE_NODE_MAX_SESSIONS", String.valueOf(maxBrowserSessions));
		}
		GenericContainer<?> chrome = new GenericContainer<>(DockerImageName.parse(image)).withSharedMemorySize(shmSize)
				.withFileSystemBind("/opt/openvidu", "/opt/openvidu").withEnv(map).withNetworkMode("host")
				.waitingFor(waitBrowser);
		return chrome;
	}

	private GenericContainer<?> firefoxContainer(String image, long shmSize, int maxBrowserSessions, boolean headless) {
		Map<String, String> map = new HashMap<>();
		map.put("SE_OPTS", "--port 4445");
		if (headless) {
			map.put("START_XVFB", "false");
		}
		if (maxBrowserSessions > 1) {
			map.put("SE_NODE_OVERRIDE_MAX_SESSIONS", "true");
			map.put("SE_NODE_MAX_SESSIONS", String.valueOf(maxBrowserSessions));
		}
		GenericContainer<?> firefox = new GenericContainer<>(DockerImageName.parse(image)).withSharedMemorySize(shmSize)
				.withFileSystemBind("/opt/openvidu", "/opt/openvidu").withEnv(map).withNetworkMode("host")
				.waitingFor(waitBrowser);
		return firefox;
	}

	private GenericContainer<?> edgeContainer(String image, long shmSize, int maxBrowserSessions, boolean headless) {
		Map<String, String> map = new HashMap<>();
		map.put("SE_OPTS", "--port 4446");
		if (headless) {
			map.put("START_XVFB", "false");
		}
		if (maxBrowserSessions > 1) {
			map.put("SE_NODE_OVERRIDE_MAX_SESSIONS", "true");
			map.put("SE_NODE_MAX_SESSIONS", String.valueOf(maxBrowserSessions));
		}
		GenericContainer<?> edge = new GenericContainer<>(DockerImageName.parse(image)).withSharedMemorySize(shmSize)
				.withFileSystemBind("/opt/openvidu", "/opt/openvidu").withEnv(map).withNetworkMode("host")
				.waitingFor(waitBrowser);
		return edge;
	}

	public void startRtspServer(boolean withAudio, boolean withVideo) throws Exception {
		GenericContainer<?> rtspServerContainer = new GenericContainer<>(DockerImageName.parse(RTSP_SERVER_IMAGE))
				.withNetworkMode("host")
				.withCommand(getFileUrl(withAudio, withVideo) + " --loop :sout=#gather:rtp{sdp=rtsp://:" + RTSP_SRT_PORT
						+ "/} :network-caching=1500 :sout-all :sout-keep");
		rtspServerContainer.start();
		containers.add(rtspServerContainer);
	}

	public void startSrtServer(boolean withAudio, boolean withVideo) throws Exception {
		GenericContainer<?> srtServerContainer = new GenericContainer<>(DockerImageName.parse(SRT_SERVER_IMAGE))
				.withNetworkMode("host").withCommand("-i " + getFileUrl(withAudio, withVideo)
						+ " -c:v libx264 -f mpegts srt://:" + RTSP_SRT_PORT + "?mode=listener");
		srtServerContainer.start();
		containers.add(srtServerContainer);
	}

	private String getFileUrl(boolean withAudio, boolean withVideo) throws Exception {
		String fileUrl;
		if (withAudio && withVideo) {
			fileUrl = "https://s3.eu-west-1.amazonaws.com/public.openvidu.io/bbb_sunflower_1080p_60fps_normal.mp4";
		} else if (!withAudio && withVideo) {
			fileUrl = "https://s3.eu-west-1.amazonaws.com/public.openvidu.io/bbb_sunflower_1080p_60fps_normal_noaudio.mp4";
		} else if (withAudio) {
			fileUrl = "https://s3.eu-west-1.amazonaws.com/public.openvidu.io/bbb_sunflower_1080p_60fps_normal_onlyaudio.mp3";
		} else {
			throw new Exception("Must have audio or video");
		}
		return fileUrl;
	}

	protected static void setUpLiveKitClient() throws NoSuchAlgorithmException {
		URI uri = null;
		try {
			uri = new URI(LIVEKIT_URL);
		} catch (URISyntaxException e) {
			Assertions.fail("Wrong LIVEKIT_URL");
		}
		String url = (("wss".equals(uri.getScheme()) || "https".equals(uri.getScheme())) ? "https" : "http") + "://"
				+ uri.getAuthority() + uri.getPath();

		LK = RoomServiceClient.create(url.toString(), LIVEKIT_API_KEY, LIVEKIT_API_SECRET, false,
				(okHttpClientBuilder) -> okHttpClientBuilder(okHttpClientBuilder));
		LK_INGRESS = IngressServiceClient.create(url.toString(), LIVEKIT_API_KEY, LIVEKIT_API_SECRET, false);
	}

	private static OkHttpClient okHttpClientBuilder(okhttp3.OkHttpClient.Builder okHttpClientBuilder) {
		TrustManager[] trustAllCerts = new TrustManager[] { new X509TrustManager() {
			@Override
			public void checkClientTrusted(java.security.cert.X509Certificate[] chain, String authType) {
			}

			@Override
			public void checkServerTrusted(java.security.cert.X509Certificate[] chain, String authType) {
			}

			@Override
			public java.security.cert.X509Certificate[] getAcceptedIssuers() {
				return new java.security.cert.X509Certificate[] {};
			}
		} };
		SSLContext sslContext = null;
		try {
			sslContext = SSLContext.getInstance("SSL");
			sslContext.init(null, trustAllCerts, new java.security.SecureRandom());
		} catch (KeyManagementException | NoSuchAlgorithmException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
		okHttpClientBuilder.sslSocketFactory(sslContext.getSocketFactory(), (X509TrustManager) trustAllCerts[0]);
		okHttpClientBuilder.hostnameVerifier((hostname, session) -> true);
		return okHttpClientBuilder.build();
	}

	protected static void loadEnvironmentVariables() {
		String appUrl = System.getProperty("APP_URL");
		if (appUrl != null) {
			APP_URL = appUrl;
		}
		log.info("Using URL {} to connect to openvidu-testapp", APP_URL);

		String livekitUrl = System.getProperty("LIVEKIT_URL");
		if (livekitUrl != null) {
			LIVEKIT_URL = livekitUrl;
		}
		log.info("Using URL {} to connect to livekit-server", LIVEKIT_URL);

		String livekitApiKey = System.getProperty("LIVEKIT_API_KEY");
		if (livekitApiKey != null) {
			LIVEKIT_API_KEY = livekitApiKey;
		}
		log.info("Using api key {} to connect to livekit-server", LIVEKIT_API_KEY);

		String livekitApiSecret = System.getProperty("LIVEKIT_API_SECRET");
		if (livekitApiSecret != null) {
			LIVEKIT_API_SECRET = livekitApiSecret;
		}
		log.info("Using api secret {} to connect to livekit-server", LIVEKIT_API_SECRET);

		String chromeVersion = System.getProperty("CHROME_VERSION");
		if (chromeVersion != null && !chromeVersion.isBlank()) {
			CHROME_VERSION = chromeVersion;
		}
		log.info("Using Chrome {}", CHROME_VERSION);

		String firefoxVersion = System.getProperty("FIREFOX_VERSION");
		if (firefoxVersion != null && !firefoxVersion.isBlank()) {
			FIREFOX_VERSION = firefoxVersion;
		}
		log.info("Using Firefox {}", FIREFOX_VERSION);

		String edgeVersion = System.getProperty("EDGE_VERSION");
		if (edgeVersion != null && !edgeVersion.isBlank()) {
			EDGE_VERSION = edgeVersion;
		}
		log.info("Using Edge {}", EDGE_VERSION);

		String openviduProLicense = System.getProperty("OPENVIDU_PRO_LICENSE");
		if (openviduProLicense != null) {
			OPENVIDU_PRO_LICENSE = openviduProLicense;
		}

		String openviduProLicenseApi = System.getProperty("OPENVIDU_PRO_LICENSE_API");
		if (openviduProLicenseApi != null) {
			OPENVIDU_PRO_LICENSE_API = openviduProLicenseApi;
		}
	}

	protected BrowserUser setupBrowser(String browser) throws Exception {

		BrowserUser browserUser = null;
		GenericContainer<?> container;
		Path path;
		boolean headless = false;

		switch (browser) {
		case "chrome":
			container = chromeContainer("selenium/standalone-chrome:" + CHROME_VERSION, 2147483648L, 1, headless);
			setupBrowserAux(BrowserNames.CHROME, container, false);
			browserUser = new ChromeUser("TestUser", 50, headless);
			break;
		case "chromeTwoInstances":
			container = chromeContainer("selenium/standalone-chrome:" + CHROME_VERSION, 2147483648L, 2, true);
			setupBrowserAux(BrowserNames.CHROME, container, false);
			browserUser = new ChromeUser("TestUser", 50, true);
			break;
		case "chromeAlternateScreenShare":
			container = chromeContainer("selenium/standalone-chrome:" + CHROME_VERSION, 2147483648L, 1, false);
			setupBrowserAux(BrowserNames.CHROME, container, false);
			browserUser = new ChromeUser("TestUser", 50, "OpenVidu TestApp");
			break;
		case "chromeAlternateFakeVideo":
			container = chromeContainer("selenium/standalone-chrome:" + CHROME_VERSION, 2147483648L, 1, false);
			setupBrowserAux(BrowserNames.CHROME, container, false);
			path = Paths.get("/opt/openvidu/barcode.y4m");
			checkMediafilePath(path);
			browserUser = new ChromeUser("TestUser", 50, path);
			break;
		case "chromeFakeAudio":
			container = chromeContainer("selenium/standalone-chrome:" + CHROME_VERSION, 2147483648L, 1, false);
			setupBrowserAux(BrowserNames.CHROME, container, false);
			path = new File("/opt/openvidu/test.wav").toPath();
			try {
				checkMediafilePath(path);
			} catch (Exception e) {
				try {
					FileUtils.copyURLToFile(
							new URL("https://openvidu-loadtest-mediafiles.s3.amazonaws.com/interview.wav"),
							new File("/opt/openvidu/test.wav"), 60000, 60000);
				} catch (FileNotFoundException e2) {
					e2.printStackTrace();
					System.err.println("exception on: downLoadFile() function: " + e.getMessage());
				}
			}
			browserUser = new ChromeUser("TestUser", 50, null, path, headless);
			break;
		case "chromeVirtualBackgroundFakeVideo":
			container = chromeContainer("selenium/standalone-chrome:" + CHROME_VERSION, 2147483648L, 1, false);
			setupBrowserAux(BrowserNames.CHROME, container, false);
			path = Paths.get("/opt/openvidu/girl.mjpeg");
			checkMediafilePath(path);
			browserUser = new ChromeUser("TestUser", 50, path, false);
			break;
		case "firefox":
			container = firefoxContainer("selenium/standalone-firefox:" + FIREFOX_VERSION, 2147483648L, 1, false);
			setupBrowserAux(BrowserNames.FIREFOX, container, false);
			browserUser = new FirefoxUser("TestUser", 50, false, headless);
			break;
		case "firefoxDisabledOpenH264":
			container = firefoxContainer("selenium/standalone-firefox:" + FIREFOX_VERSION, 2147483648L, 1, true);
			setupBrowserAux(BrowserNames.FIREFOX, container, false);
			browserUser = new FirefoxUser("TestUser", 50, true, headless);
			break;
		case "edge":
			container = edgeContainer("selenium/standalone-edge:" + EDGE_VERSION, 2147483648L, 1, false);
			setupBrowserAux(BrowserNames.EDGE, container, false);
			browserUser = new EdgeUser("TestUser", 50, headless);
			break;
		default:
			log.error("Browser {} not recognized", browser);
		}

		this.browserUsers.add(browserUser);
		return browserUser;
	}

	private static boolean setupBrowserAux(BrowserNames browser, GenericContainer<?> container, boolean forceRestart) {
		if (isRemote(browser)) {
			String dockerImage = container.getDockerImageName();
			String ps = commandLine.executeCommand("docker ps | grep " + dockerImage, 30);
			boolean containerAlreadyRunning = container.isRunning() || !ps.isBlank();
			if (forceRestart && containerAlreadyRunning) {
				container.stop();
			}
			if (!containerAlreadyRunning) {
				container.start();
				containers.add(container);
				return true;
			}
		}
		return false;
	}

	private static boolean isRemote(BrowserNames browser) {
		String remoteUrl = null;
		switch (browser) {
		case CHROME:
			remoteUrl = System.getProperty("REMOTE_URL_CHROME");
			break;
		case FIREFOX:
			remoteUrl = System.getProperty("REMOTE_URL_FIREFOX");
			break;
		case OPERA:
			remoteUrl = System.getProperty("REMOTE_URL_OPERA");
			break;
		case EDGE:
			remoteUrl = System.getProperty("REMOTE_URL_EDGE");
			break;
		case ANDROID:
			return true;
		}
		return remoteUrl != null;
	}

	@AfterEach
	protected void dispose() {

		// Close all remaining Rooms
		this.closeAllRooms(LK);

		// Dispose all browsers users
		Iterator<BrowserUser> it1 = browserUsers.iterator();
		while (it1.hasNext()) {
			BrowserUser u = it1.next();
			u.dispose();
			it1.remove();
		}

		// Stop and remove all browser containers if necessary
		Iterator<GenericContainer<?>> it2 = containers.iterator();
		List<String> waitUntilContainerIsRemovedCommands = new ArrayList<>();
		containers.forEach(c -> {
			waitUntilContainerIsRemovedCommands
					.add("while docker inspect " + c.getContainerId() + " >/dev/null 2>&1; do sleep 1; done");
		});
		while (it2.hasNext()) {
			GenericContainer<?> c = it2.next();
			stopContainerIfPossible(c);
			it2.remove();
		}
		waitUntilContainerIsRemovedCommands.forEach(command -> {
			commandLine.executeCommand(command, 30);
		});
	}

	private void stopContainerIfPossible(GenericContainer<?> container) {
		if (container != null && container.isRunning()) {
			container.stop();
			container.close();
		}
	}

	protected void closeAllRooms(RoomServiceClient client) {
		try {
			Response<List<Room>> response = client.listRooms().execute();
			if (response.isSuccessful()) {
				List<Room> roomList = response.body();
				if (roomList != null) {
					client.listRooms().execute().body().forEach(r -> {
						log.info("Closing existing room " + r.getName());
						try {
							log.info("Response: " + client.deleteRoom(r.getName()).execute().code());
						} catch (IOException e) {
							log.error("Error closing room " + r.getName(), e);
						}
					});
				}
			} else {
				log.error("Error listing rooms: " + response.errorBody());
			}
		} catch (Exception e) {
			log.error("Error closing rooms: {}", e.getMessage());
		}
	}

	protected void deleteAllIngresses(IngressServiceClient client) {
		try {
			Response<List<IngressInfo>> response = client.listIngress().execute();
			if (response.isSuccessful()) {
				List<IngressInfo> ingressList = response.body();
				if (ingressList != null) {
					client.listIngress().execute().body().forEach(i -> {
						log.info("Deleting existing ingress " + i.getName());
						try {
							log.info("Response: " + client.deleteIngress(i.getIngressId()).execute().code());
						} catch (IOException e) {
							log.error("Error deleting ingress " + i.getName(), e);
						}
					});
				}
			} else {
				log.error("Error listing ingresses: " + response.errorBody());
			}
		} catch (Exception e) {
			log.error("Error deleting ingresses: {}", e.getMessage());
		}
	}

	private void checkMediafilePath(Path path) throws Exception {
		if (!Files.exists(path)) {
			throw new Exception("File " + path.toAbsolutePath().toString() + " does not exist");
		} else if (!Files.isReadable(path)) {
			throw new Exception("File " + path.toAbsolutePath().toString() + " exists but is not readable");
		}
	}
}
