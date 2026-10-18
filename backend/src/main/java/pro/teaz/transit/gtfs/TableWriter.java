package pro.teaz.transit.gtfs;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.Collections;
import java.util.List;

import org.postgresql.PGConnection;
import org.postgresql.copy.CopyIn;

/**
 * Bulk-loads rows into one table. Postgres gets COPY (minutes instead of hours
 * for the tens of millions of stop times of all of Germany); other databases
 * (H2 in dev and tests) get batched INSERTs.
 */
abstract class TableWriter implements AutoCloseable {

	static TableWriter open(Connection connection, String table, List<String> columns) throws SQLException {
		if (connection.isWrapperFor(PGConnection.class)) {
			return new Copy(connection.unwrap(PGConnection.class), table, columns);
		}
		return new Batch(connection, table, columns);
	}

	private long rows;

	/** One row, values in the order of the columns given to {@link #open}. Null = SQL NULL. */
	final void row(Object... values) throws SQLException {
		write(values);
		rows++;
	}

	final long rows() {
		return rows;
	}

	protected abstract void write(Object[] values) throws SQLException;

	@Override
	public abstract void close() throws SQLException;

	private static final class Copy extends TableWriter {

		private static final int BUFFER = 1 << 16;

		private final CopyIn copy;
		private final StringBuilder buffer = new StringBuilder(BUFFER + 1024);

		Copy(PGConnection connection, String table, List<String> columns) throws SQLException {
			copy = connection.getCopyAPI().copyIn("COPY " + table + " (" + String.join(", ", columns) + ") FROM STDIN");
		}

		@Override
		protected void write(Object[] values) throws SQLException {
			for (int i = 0; i < values.length; i++) {
				if (i > 0) buffer.append('\t');
				appendValue(values[i]);
			}
			buffer.append('\n');
			if (buffer.length() >= BUFFER) flush();
		}

		/** COPY text format: \N for NULL, backslash escapes for the separators. */
		private void appendValue(Object value) {
			if (value == null) {
				buffer.append("\\N");
				return;
			}
			String text = value instanceof Boolean b ? (b ? "t" : "f") : value.toString();
			for (int i = 0; i < text.length(); i++) {
				char c = text.charAt(i);
				switch (c) {
					case '\\' -> buffer.append("\\\\");
					case '\t' -> buffer.append("\\t");
					case '\n' -> buffer.append("\\n");
					case '\r' -> buffer.append("\\r");
					default -> buffer.append(c);
				}
			}
		}

		private void flush() throws SQLException {
			byte[] bytes = buffer.toString().getBytes(StandardCharsets.UTF_8);
			copy.writeToCopy(bytes, 0, bytes.length);
			buffer.setLength(0);
		}

		@Override
		public void close() throws SQLException {
			try {
				flush();
				copy.endCopy();
			} finally {
				if (copy.isActive()) copy.cancelCopy();
			}
		}
	}

	private static final class Batch extends TableWriter {

		private static final int BATCH = 5_000;

		private final PreparedStatement statement;
		private int pending;

		Batch(Connection connection, String table, List<String> columns) throws SQLException {
			String placeholders = String.join(", ", Collections.nCopies(columns.size(), "?"));
			statement = connection.prepareStatement(
					"INSERT INTO " + table + " (" + String.join(", ", columns) + ") VALUES (" + placeholders + ")");
		}

		@Override
		protected void write(Object[] values) throws SQLException {
			for (int i = 0; i < values.length; i++) {
				Object value = values[i] instanceof LocalDate date ? java.sql.Date.valueOf(date) : values[i];
				statement.setObject(i + 1, value);
			}
			statement.addBatch();
			if (++pending >= BATCH) {
				statement.executeBatch();
				pending = 0;
			}
		}

		@Override
		public void close() throws SQLException {
			try {
				if (pending > 0) statement.executeBatch();
			} finally {
				statement.close();
			}
		}
	}
}
