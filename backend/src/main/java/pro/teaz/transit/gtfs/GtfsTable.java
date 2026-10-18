package pro.teaz.transit.gtfs;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

import de.siegmar.fastcsv.reader.CsvReader;
import de.siegmar.fastcsv.reader.CsvRecord;

/**
 * Streams the rows of one GTFS file. Columns are looked up by name once, from
 * the header, since feeds order (and omit) optional columns as they like.
 */
final class GtfsTable implements AutoCloseable, Iterable<GtfsTable.Row> {

	private final CsvReader<CsvRecord> reader;
	private final Iterator<CsvRecord> records;
	private final Map<String, Integer> columns = new HashMap<>();

	GtfsTable(InputStream in) throws IOException {
		BufferedReader text = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8), 1 << 16);
		// Byte order mark, written by some exporters in front of the header.
		text.mark(1);
		if (text.read() != '﻿') text.reset();
		reader = CsvReader.builder().ofCsvRecord(text);
		records = reader.iterator();
		if (records.hasNext()) {
			CsvRecord header = records.next();
			for (int i = 0; i < header.getFieldCount(); i++) {
				columns.put(header.getField(i).trim(), i);
			}
		}
	}

	/** Column index for {@link Row#get(int)}, -1 if the file doesn't have it. */
	int column(String name) {
		return columns.getOrDefault(name, -1);
	}

	/** Like {@link #column}, but the file must have it. */
	int requiredColumn(String name) {
		int column = column(name);
		if (column < 0) throw new IllegalStateException("GTFS file lacks column " + name);
		return column;
	}

	@Override
	public Iterator<Row> iterator() {
		return new Iterator<>() {
			@Override
			public boolean hasNext() {
				return records.hasNext();
			}

			@Override
			public Row next() {
				return new Row(records.next());
			}
		};
	}

	@Override
	public void close() throws IOException {
		reader.close();
	}

	record Row(CsvRecord record) {

		/** The trimmed value, "" for a missing column or field. */
		String get(int column) {
			if (column < 0 || column >= record.getFieldCount()) return "";
			return record.getField(column).trim();
		}

		/** Like {@link #get(int)}, but null instead of "". */
		String getOrNull(int column) {
			String value = get(column);
			return value.isEmpty() ? null : value;
		}
	}
}
