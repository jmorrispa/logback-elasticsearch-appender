package com.internetitem.logback.elasticsearch;

import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.Context;
import ch.qos.logback.core.spi.ContextAwareBase;
import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonGenerator;

import javax.xml.bind.DatatypeConverter;
import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.List;

public class ElasticsearchPublisher extends ContextAwareBase implements Runnable {

	public static final String THREAD_NAME = "es-writer";

	private volatile List<ILoggingEvent> events;
	private StringWriter sendBuffer;

	private Object lock;
	private String indexString;
	private JsonFactory jf;

	private URL url;
	private Settings settings;

	private List<PropertyAndEncoder> propertyList;

	private volatile boolean working;
	private volatile boolean bufferExceeded;

	public ElasticsearchPublisher(Context context, String index, String type, URL url, Settings settings, ElasticsearchProperties properties) throws IOException {
		setContext(context);
		this.events = new ArrayList<ILoggingEvent>();
		this.lock = new Object();
		this.jf = new JsonFactory();
		this.jf.setRootValueSeparator(null);

		this.indexString = generateIndexString(index, type);
		this.sendBuffer = new StringWriter();

		this.url = url;
		this.settings = settings;
		this.propertyList = setupPropertyList(getContext(), properties);
	}

	private static List<PropertyAndEncoder> setupPropertyList(Context context, ElasticsearchProperties properties) {
		List<PropertyAndEncoder> list = new ArrayList<PropertyAndEncoder>(properties.getProperties().size());
		if (properties != null) {
			for (Property property : properties.getProperties()) {
				list.add(new PropertyAndEncoder(property, context));
			}
		}
		return list;
	}

	private String generateIndexString(String index, String type) throws IOException {
		StringWriter writer = new StringWriter();
		JsonGenerator gen = jf.createGenerator(writer);
		gen.writeStartObject();
		gen.writeObjectFieldStart("index");
		gen.writeObjectField("_index", index);
		if (type != null) {
			gen.writeObjectField("_type", type);
		}
		gen.writeEndObject();
		gen.writeEndObject();
		gen.writeRaw('\n');
		gen.close();
		return writer.toString();
	}

	public void addEvent(ILoggingEvent event) {
		synchronized (lock) {
			if (!bufferExceeded) {
				events.add(event);
			}

			if (!working) {
				working = true;
				Thread thread = new Thread(this, THREAD_NAME);
				thread.start();
			}
		}
	}

	public void run() {
		int currentTry = 1;
		int maxRetries = settings.getMaxRetries();
		while (true) {
			try {
				Thread.sleep(settings.getSleepTime());

				List<ILoggingEvent> eventsCopy = null;
				synchronized (lock) {
					if (!events.isEmpty()) {
						eventsCopy = events;
						events = new ArrayList<ILoggingEvent>();
						currentTry = 1;
					}

					if (eventsCopy == null) {
						if (sendBuffer == null) {
							// all done
							working = false;
							return;
						} else {
							// Nothing new, must be a retry
							if (currentTry > maxRetries) {
								// Oh well, better luck next time
								working = false;
								return;
							}
						}
					}
				}

				if (eventsCopy != null) {
					serializeEventsToBuffer(eventsCopy);
				}

				sendEvents();
			} catch (Exception e) {
				addError("Failed to send events to Elasticsearch (try " + currentTry + " of " + maxRetries + "): " + e.getMessage(), e);
				if (settings.isErrorsToStderr()) {
					System.err.println("[" + new Date().toString() + "] Failed to send events to Elasticsearch (try " + currentTry + " of " + maxRetries + "): " + e.getMessage());
				}
				currentTry++;
			}
		}
	}

	private void sendEvents() throws IOException {
		if (settings.isDebug()) {
			System.err.println(sendBuffer);
			sendBuffer = null;
			return;
		}

		HttpURLConnection urlConnection = (HttpURLConnection)url.openConnection();
		try {
			urlConnection.setDoInput(true);
			urlConnection.setDoOutput(true);
			urlConnection.setReadTimeout(settings.getReadTimeout());
			urlConnection.setConnectTimeout(settings.getConnectTimeout());
			urlConnection.setRequestMethod("POST");

			Writer writer = new OutputStreamWriter(urlConnection.getOutputStream(), "UTF-8");
			writer.write(sendBuffer.toString());
			writer.flush();
			writer.close();

			int rc = urlConnection.getResponseCode();
			if (rc != 200) {
				String data = slurpErrors(urlConnection);
				throw new IOException("Got response code [" + rc + "] from server with data " + data);
			}
		} finally {
			urlConnection.disconnect();
		}

		sendBuffer.getBuffer().setLength(0);
		if (bufferExceeded) {
			addInfo("Send queue cleared - log messages will no longer be lost");
			bufferExceeded = false;
		}
	}

	private String slurpErrors(HttpURLConnection urlConnection) {
		try {
			InputStream stream = urlConnection.getErrorStream();
			if (stream == null) {
                return "<no data>";
            }

			StringBuilder builder = new StringBuilder();
			InputStreamReader reader = new InputStreamReader(stream, "UTF-8");
			char[] buf = new char[2048];
			int numRead;
			while ((numRead = reader.read(buf)) > 0) {
                builder.append(buf, 0, numRead);
            }
			return builder.toString();
		} catch (Exception e) {
			return "<error retrieving data: " + e.getMessage() + ">";
		}
	}

	private void serializeEventsToBuffer(List<ILoggingEvent> eventsCopy) throws IOException {
		JsonGenerator gen = jf.createGenerator(sendBuffer);
		for (ILoggingEvent event : eventsCopy) {
			gen.writeRaw(indexString);
			serializeEvent(gen, event);
			gen.writeRaw('\n');
		}
		gen.close();

		if (sendBuffer.getBuffer().length() > settings.getMaxQueueSize() && !bufferExceeded) {
			addWarn("Send queue maximum size exceeded - log messages will be lost until the buffer is cleared");
			bufferExceeded = true;
		}
	}

	private void serializeEvent(JsonGenerator gen, ILoggingEvent event) throws IOException {
		gen.writeStartObject();

		gen.writeObjectField("@timestamp", getTimestamp(event.getTimeStamp()));
		gen.writeObjectField("message", event.getMessage());

		for (PropertyAndEncoder pae : propertyList) {
			String value = pae.encode(event);
			if (pae.allowEmpty() || (value != null && !value.isEmpty())) {
				gen.writeObjectField(pae.getName(), value);
			}
		}

		gen.writeEndObject();
	}

	private String getTimestamp(long timestamp) {
		Calendar cal = Calendar.getInstance();
		cal.setTimeInMillis(timestamp);
		return DatatypeConverter.printDateTime(cal);
	}

}
