package pro.teaz.transit.gtfs;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import org.springframework.core.io.ClassPathResource;

/** The text files of one GTFS feed: a downloaded zip, or the bundled sample. */
interface GtfsSource extends AutoCloseable {

	/** The file's content, or null if the feed doesn't have it (optional files). */
	InputStream open(String fileName) throws IOException;

	@Override
	void close() throws IOException;

	static GtfsSource zip(Path zip) throws IOException {
		ZipFile file = new ZipFile(zip.toFile());
		return new GtfsSource() {
			@Override
			public InputStream open(String fileName) throws IOException {
				// Some feeds put the files into a folder inside the zip.
				ZipEntry entry = file.stream()
						.filter(e -> !e.isDirectory() && (e.getName().equals(fileName) || e.getName().endsWith("/" + fileName)))
						.findFirst().orElse(null);
				return entry == null ? null : file.getInputStream(entry);
			}

			@Override
			public void close() throws IOException {
				file.close();
			}
		};
	}

	static GtfsSource classpath(String folder) {
		return new GtfsSource() {
			@Override
			public InputStream open(String fileName) throws IOException {
				ClassPathResource resource = new ClassPathResource(folder + "/" + fileName);
				return resource.exists() ? resource.getInputStream() : null;
			}

			@Override
			public void close() {
			}
		};
	}
}
