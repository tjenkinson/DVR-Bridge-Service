package uk.co.la1tv.dvrBridgeService.handlers;

import java.util.Map;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import uk.co.la1tv.dvrBridgeService.httpExceptions.InternalServerErrorException;
import uk.co.la1tv.dvrBridgeService.streamManager.SiteStream;
import uk.co.la1tv.dvrBridgeService.streamManager.StreamManager;

@Component
public class StopRequestHandler implements IRequestHandler {

	@Autowired
	private StreamManager streamManager;
	
	@Override
	public String getType() {
		return "STOP";
	}

	@Override
	public Object handle(long streamId, Map<String, String[]> requestParameters) {
		SiteStream stream = streamManager.getStream(streamId);
		if (!stream.stopCapture()) {
			throw(new InternalServerErrorException("Unable to stop the capture for some reason."));
		}
		return null;
	}

}
