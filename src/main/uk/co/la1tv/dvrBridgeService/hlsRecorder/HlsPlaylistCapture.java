package uk.co.la1tv.dvrBridgeService.hlsRecorder;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.ArrayList;
import java.util.Timer;
import java.util.TimerTask;

import org.apache.commons.exec.CommandLine;
import org.apache.commons.exec.DefaultExecutor;
import org.apache.commons.exec.PumpStreamHandler;
import org.apache.log4j.Logger;
import org.json.simple.JSONArray;
import org.json.simple.JSONObject;
import org.json.simple.JSONValue;
import org.json.simple.parser.ParseException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Component;

import uk.co.la1tv.dvrBridgeService.helpers.FileHelper;
import uk.co.la1tv.dvrBridgeService.hlsRecorder.exceptions.IncompletePlaylistException;
import uk.co.la1tv.dvrBridgeService.hlsRecorder.exceptions.PlaylistRequestException;

/**
 * An object that represents a hls playlist recording.
 */
@Component
@Scope("prototype")
public class HlsPlaylistCapture {

	private static Logger logger = Logger.getLogger(HlsPlaylistCapture.class);
	
	private final Object lock = new Object();
	private final Object playlistGenerationLock = new Object();
	
	@Value("${m3u8Parser.nodePath}")
	private String nodePath;
	
	@Value("${m3u8Parser.applicationJsPath}")
	private String m3u8ParserApplicationPath;
	
	@Value("${app.playlistUpdateInterval}")
	private int playlistUpdateInterval;
	
	@Autowired
	private HlsSegmentFileStore hlsSegmentFileStore;
	
	private final HlsPlaylist playlist;
	private int captureState = 0; // 0=not started, 1=capturing, 2=stopped
	private Long captureStartTime = null; // start time in unix time in milliseconds
	private double captureDuration = 0; // the number of seconds currently captured
	// the segments that have been downloaded in order
	private ArrayList<HlsSegment> segments = new ArrayList<>();
	// the maximum length that a segment can be (milliseconds)
	// retrieved from the playlist
	private Float segmentTargetDuration = null;
	private final Timer updateTimer = new Timer();
	private final IPlaylistUpdatedCallback playlistUpdatedCallback;
	private String generatedPlaylistContent = null;
	
	/**
	 * Create a new object which represents a capture file for a playlist.
	 * @param playlist The playlist to generate a capture from.
	 */
	public HlsPlaylistCapture(HlsPlaylist playlist, IPlaylistUpdatedCallback playlistUpdatedCallback) {
		this.playlist = playlist;
		this.playlistUpdatedCallback = playlistUpdatedCallback;
	}
	
	/**
	 * Start capturing. This operation can only be performed once.
	 * False is returned if the capture could not be started for some reason.
	 */
	public boolean startCapture() {
		synchronized(lock) {
			if (captureState != 0) {
				throw(new RuntimeException("Invalid capture state."));
			}
			try {
				retrievePlaylistMetadata();
			} catch (PlaylistRequestException e) {
				logger.warn("An error occurred retrieving the playlist so capture could not be started.");
				return false;
			}
			captureState = 1;
			captureStartTime = System.currentTimeMillis();
			updateTimer.schedule(new UpdateTimerTask(), 0, playlistUpdateInterval);
			generatePlaylistContent();
			return true;
		}
	}
	
	/**
	 * Stop capturing. This operation can only be performed once.
	 */
	public void stopCapture() {
		synchronized(lock) {
			if (captureState != 1) {
				throw(new RuntimeException("Invalid capture state."));
			}
			updateTimer.cancel();
			updateTimer.purge();
			captureState = 2;
			generatePlaylistContent();
		}
	}
	
	/**
	 * Get the unix timestamp (seconds) when the capture started.
	 * @return the unix timestamp (seconds)
	 */
	public long getCaptureStartTime() {
		synchronized(lock) {
			if (captureState == 0) {
				throw(new RuntimeException("Capture not started yet."));
			}
			return captureStartTime;
		}
	}
	
	/**
	 * Get the duration of the capture (seconds). This is dynamic and will
	 * update if a capture is currently in progress.
	 * @return
	 */
	public double getCaptureDuration() {
		synchronized(lock) {
			if (captureState == 0) {
				throw(new RuntimeException("Capture not started yet."));
			}
			return captureDuration;
		}
	}
	
	/**
	 * Determine if the stream is currently being captured.
	 * @return
	 */
	public boolean isCapturing() {
		return captureState == 1;
	}
	
	
	/**
	 * Get the contents of the playlist file that represents this capture
	 */
	public String getPlaylistContent() {
		if (captureState == 0) {
			throw(new RuntimeException("Capture not started yet."));
		}
		return generatedPlaylistContent;
	}
	
	
	/**
	 * Generate the playlist content that can be retrieved with getPlaylistContent
	 */
	private void generatePlaylistContent() {
		synchronized(playlistGenerationLock) {
			String contents = "";
			contents += "#EXTM3U\n";
			contents += "#EXT-X-PLAYLIST-TYPE:EVENT\n";
			contents += "#EXT-X-TARGETDURATION:"+segmentTargetDuration+"\n";
			contents += "#EXT-X-MEDIA-SEQUENCE:0\n";
			
			// segments might be in the array that haven't actually downloaded yet (or where their download has failed)
			boolean allSegmentsDownloaded = true;
			for(HlsSegment segment : segments) {
				
				HlsSegmentFile segmentFile = segment.getSegmentFile();
				if (segmentFile.getState() == HlsSegmentFileState.DOWNLOAD_FAILED) {
					// can never put anything else in this playlist, because will always be
					// missing this chunk
					break;
				}
				else if (segmentFile.getState() != HlsSegmentFileState.DOWNLOADED) {
					// can't get any more segments until this one has the file downloaded.
					allSegmentsDownloaded = false;
					break;
				}
				
				if (segment.getDiscontinuityFlag()) {
					contents += "#EXT-X-DISCONTINUITY\n";
				}
				contents += "#EXTINF:"+segment.getDuration()+",\n";
				contents += segmentFile.getFileUrl().toExternalForm()+"\n";
			}
			
			if (captureState == 2 && allSegmentsDownloaded) {
				// recording has finished, and has all segments, so mark event as finished
				contents += "#EXT-X-ENDLIST\n";
			}
			
			if (generatedPlaylistContent != null && contents.equals(generatedPlaylistContent)) {
				// no change
				return;
			}
			generatedPlaylistContent = contents;
			if (playlistUpdatedCallback != null) {
				playlistUpdatedCallback.onPlaylistUpdated(this);
			}
		}
	}
	
	/**
	 * Get any necessary metadata about the playlist.
	 * e.g the segmentTargetDuration
	 * @throws PlaylistRequestException 
	 */
	private void retrievePlaylistMetadata() throws PlaylistRequestException {
		JSONObject info = getPlaylistInfo();
		JSONObject properties = (JSONObject) info.get("properties");
		Object targetDuration = properties.get("targetDuration");
		if (targetDuration == null) {
			throw(new IncompletePlaylistException());
		}
		String durationStr = String.valueOf(targetDuration);
		float duration = Float.valueOf(durationStr);
		segmentTargetDuration = duration;
	}
	
	/**
	 * Make request to get playlist, parse it, and return info.
	 * @return
	 * @throws PlaylistRequestException 
	 */
	private JSONObject getPlaylistInfo() throws PlaylistRequestException {
		String playlistUrl = playlist.getUrl().toExternalForm();
		
		ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
		CommandLine commandLine = new CommandLine(FileHelper.format(nodePath));
		commandLine.addArgument(FileHelper.format(m3u8ParserApplicationPath));
		commandLine.addArgument(playlistUrl);
		DefaultExecutor exec = new DefaultExecutor();
		// handle the stdout stream, ignore error stream
		PumpStreamHandler streamHandler = new PumpStreamHandler(outputStream, null);
		exec.setStreamHandler(streamHandler);
		int exitVal;
		try {
			exitVal = exec.execute(commandLine);
		} catch (IOException e1) {
			e1.printStackTrace();
			logger.warn("Error trying to retrieve playlist information.");
			throw(new PlaylistRequestException());
		}
	    if (exitVal != 0) {
			logger.warn("Error trying to retrieve playlist information.");
			throw(new PlaylistRequestException());
		}
		String playlistInfoJsonString = outputStream.toString();
		JSONObject playlistInfo = null;
		try {
			playlistInfo = (JSONObject) JSONValue.parseWithException(playlistInfoJsonString);
		} catch (ParseException e) {
			e.printStackTrace();
			logger.warn("Error trying to retrieve playlist information.");
			throw(new PlaylistRequestException());
		}
		return playlistInfo;
	}
	
	/**
	 * Extract the playlist items out of playlistInfo.
	 * Returns null if there was an error.
	 * @return
	 */
	private JSONArray extractPlaylistItems(JSONObject playlistInfo) {
		JSONObject items = (JSONObject) playlistInfo.get("items");
		JSONArray playlistItems = (JSONArray) items.get("PlaylistItem");
		return playlistItems;
	}
	
	/**
	 * Responsible for retrieving new segments as they become available.
	 */
	private class UpdateTimerTask extends TimerTask {

		@Override
		public void run() {
			synchronized(lock) {
				if (captureState != 1) {
					return;
				}
				
				int numSegments = segments.size();
				Integer lastSequenceNumber = numSegments > 0 ? segments.get(numSegments-1).getSequenceNumber() : null;
				// the next sequence number will always be one more than the last one as per the specification
				// if we don't have any segments yet then we will set this to null which will mean just the newest chunk
				// will be retrieved
				Integer nextSequenceNumber = lastSequenceNumber != null ? lastSequenceNumber+1 : null;
				JSONObject playlistInfo = null;
				try {
					playlistInfo = getPlaylistInfo();
				} catch (PlaylistRequestException e) {
					logger.warn("Error retrieving playlist so capture stopped.");
					stopCapture();
				}
				
				JSONObject properties = (JSONObject) playlistInfo.get("properties");
				int firstSequenceNumber = Integer.valueOf(String.valueOf(properties.get("mediaSequence")));
				
				JSONArray items = extractPlaylistItems(playlistInfo);
				if (!items.isEmpty()) {
					if (nextSequenceNumber != null) {
						int seqNum = firstSequenceNumber;
						for(int i=0; i<items.size(); i++) {
							if (seqNum >= nextSequenceNumber) {
								// this is a new item
								addNewSegment((JSONObject) items.get(i), seqNum);
							}
							seqNum++;
						}
					}
					else {
						// just add the newest segment
						addNewSegment((JSONObject) items.get(items.size()-1), firstSequenceNumber+items.size()-1);
					}
				}
			}
		}
	}
	
	private void addNewSegment(JSONObject item, int seqNum) {
		JSONObject itemProperties = (JSONObject) item.get("properties");
		float duration = Float.parseFloat(String.valueOf(itemProperties.get("duration")));
		boolean discontinuityFlag = itemProperties.get("discontinuity") != null;
		URL segmentUrl = null;
		try {
			segmentUrl = new URL(playlist.getUrl(), String.valueOf(itemProperties.get("uri")));
		} catch (MalformedURLException e) {
			throw(new IncompletePlaylistException());
		}
		HlsSegmentFile hlsSegmentFile = hlsSegmentFileStore.getSegment(segmentUrl);
		hlsSegmentFile.registerStateChangeCallback(new HlsSegmentFileStateChangeHandler(hlsSegmentFile));
		synchronized(lock) {
			segments.add(new HlsSegment(hlsSegmentFile, seqNum, duration, discontinuityFlag));
		}
	}
	
	private class HlsSegmentFileStateChangeHandler implements IHlsSegmentFileStateChangeCallback {
		
		// the HlsSegmentFile that this handler has been set up for
		private final HlsSegmentFile hlsSegmentFile;
		private boolean handled = false;
		
		public HlsSegmentFileStateChangeHandler(HlsSegmentFile hlsSegmentFile) {
			this.hlsSegmentFile = hlsSegmentFile;
			handleStateChange(hlsSegmentFile.getState());
		}
		
		private synchronized void handleStateChange(HlsSegmentFileState state) {
			if (handled) {
				// this can happen if the event is fired just as this is already being
				// handled from the constructor
				return;
			}
			
			if (state == HlsSegmentFileState.DOWNLOADED) {
				// regenerate the playlist content.
				generatePlaylistContent();
			}
			else if (state == HlsSegmentFileState.DOWNLOAD_FAILED) {
				logger.warn("Error downloading playlist chunk so stopping capture.");
				synchronized(lock) {
					if (captureState != 2) {
						// capture hasn't already been stopped by an earlier failure
						stopCapture();
					}
				}
			}
			else {
				return;
			}
			
			// don't care about any more state changes so remove handler
			// so can be garbage collected
			hlsSegmentFile.unregisterStateChangeCallback(this);
			handled = true;
		}
		
		@Override
		public void onStateChange(HlsSegmentFileState state) {
			handleStateChange(state);
		}
		
	}
	
}
