package io.quantumdb.core.backends.postgresql.migrator;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import com.google.common.base.Joiner;
import com.google.common.base.Strings;
import com.google.common.collect.Maps;
import io.quantumdb.core.schema.definitions.Column;
import io.quantumdb.core.schema.definitions.ForeignKey;
import io.quantumdb.core.schema.definitions.ForeignKey.Action;
import io.quantumdb.core.schema.definitions.Function;
import io.quantumdb.core.schema.definitions.Index;
import io.quantumdb.core.schema.definitions.Sequence;
import io.quantumdb.core.schema.definitions.Table;
import io.quantumdb.core.utils.QueryBuilder;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class StatementExecutor {

	public void create(Connection connection, Collection<Table> tables) throws SQLException {
		createTables(connection, tables);
		createForeignKeys(connection, tables);
	}

	public void createTables(Connection connection, Collection<Table> tables) throws SQLException {
		for (Table table : tables) {
			createTable(connection, table);
		}
	}

	public void createForeignKeys(Connection connection, Collection<Table> tables) throws SQLException {
		for (Table table : tables) {
			createForeignKeys(connection, table);
		}
	}

	public void createIndexes(Connection connection, Collection<Table> tables) throws SQLException {
		for (Table table : tables) {
			createIndexes(connection, table);
		}
	}

	public void createFunctions(Connection connection, Collection<Function> functions) throws SQLException {
		for (Function function : functions) {
			createFunction(connection, function);
		}
	}

	public void dropFunctions(Connection connection, Collection<Function> functions) throws SQLException {
		for (Function function : functions) {
			dropFunction(connection, function);
		}
	}

	private void createTable(Connection connection, Table table) throws SQLException {
		log.info("Creating table: {}", table.getName());
		Map<String, String> sequences = Maps.newHashMap();

		QueryBuilder queryBuilder = new QueryBuilder();
		queryBuilder.append("CREATE TABLE " + table.getName() + " (");

		boolean shouldOwnSequence = false;
		boolean columnAdded = false;
		for (Column column : table.getColumns()) {
			if (columnAdded) {
				queryBuilder.append(", ");
			}

			queryBuilder.append(column.getName() + " " + column.getType());
			if (column.isNotNull()) {
				queryBuilder.append("NOT NULL");
			}

			if (column.isAutoIncrement()) {
				Sequence sequence = column.getSequence();
				if (sequence == null) {
					String sequenceName = table.getName() + "_" + column.getName() + "_seq";
					sequence = new Sequence(sequenceName);
					table.getParent().addSequence(sequence);
					column.modifyDefaultValue(sequence);

					shouldOwnSequence = true;
					execute(connection, new QueryBuilder("CREATE SEQUENCE " + sequenceName + ";"));
				}

				sequences.put(sequence.getName(), column.getName());
				queryBuilder.append("DEFAULT NEXTVAL('" + sequence.getName() + "')");
			}
			else if (!Strings.isNullOrEmpty(column.getDefaultValue())) {
				queryBuilder.append("DEFAULT " + column.getDefaultValue());
			}

			columnAdded = true;
		}

		List<String> identityColumns = table.getIdentityColumns().stream()
				.map(Column::getName)
				.collect(Collectors.toList());

		if (!identityColumns.isEmpty()) {
			queryBuilder.append(", PRIMARY KEY(" + Joiner.on(", ").join(identityColumns) + ")");
		}

		queryBuilder.append(")");

		execute(connection, queryBuilder);

		if (shouldOwnSequence) {
			for (Map.Entry<String, String> sequence : sequences.entrySet()) {
				execute(connection, new QueryBuilder()
						.append("ALTER SEQUENCE " + sequence.getKey())
						.append("OWNED BY " + table.getName() + "." + sequence.getValue()));
			}
		}
	}

	private void createForeignKeys(Connection connection, Table table) throws SQLException {
		for (ForeignKey foreignKey : table.getForeignKeys()) {
			QueryBuilder queryBuilder = new QueryBuilder();
			queryBuilder.append("ALTER TABLE " + table.getName());
			queryBuilder.append("ADD CONSTRAINT " + foreignKey.getForeignKeyName());
			queryBuilder.append("FOREIGN KEY (" + Joiner.on(", ").join(foreignKey.getReferencingColumns()) + ")");
			queryBuilder.append("REFERENCES " + foreignKey.getReferredTableName());
			queryBuilder.append("(" + Joiner.on(", ").join(foreignKey.getReferredColumns()) + ")");
			queryBuilder.append("ON UPDATE " + valueOf(foreignKey.getOnUpdate()));
			queryBuilder.append("ON DELETE " + valueOf(foreignKey.getOnDelete()));
			queryBuilder.append("DEFERRABLE");

			log.info("Creating foreign key: {}", foreignKey.getForeignKeyName());
			execute(connection, queryBuilder);
		}
	}

	private void createIndexes(Connection connection, Table table) throws SQLException {
		for (Index index : table.getIndexes()) {
			QueryBuilder queryBuilder = new QueryBuilder();
			queryBuilder.append("CREATE");
			if (index.isUnique()) {
				queryBuilder.append("UNIQUE");
			}
			queryBuilder.append("INDEX " + index.getIndexName());
			queryBuilder.append("ON " + index.getParent().getName());
			queryBuilder.append("(" + Joiner.on(", ").join(index.getColumns()) + ");");

			log.info("Creating index key: {}", index.getIndexName());
			execute(connection, queryBuilder);
		}
	}

	private void createFunction(Connection connection, Function function) throws SQLException {
		String parameters = function.getParameters().entrySet().stream()
				.map(entry -> entry.getKey() + " " + entry.getValue().getNotation())
				.collect(Collectors.joining(", "));

		QueryBuilder queryBuilder = new QueryBuilder()
				.append("CREATE FUNCTION " + function.getName())
				.append("(" + parameters + ")")
				.append("RETURNS " + function.getReturnType().getNotation())
				.append("AS $$")
				.append(function.getBody())
				.append("$$ LANGUAGE plpgsql;");

		log.info("Creating function: {}", function.getName());
		execute(connection, queryBuilder);
	}

	private void dropFunction(Connection connection, Function function) throws SQLException {
		String parameters = function.getParameters().entrySet().stream()
				.map(entry -> entry.getKey() + " " + entry.getValue().getNotation())
				.collect(Collectors.joining(", "));

		QueryBuilder queryBuilder = new QueryBuilder()
				.append("DROP FUNCTION " + function.getName())
				.append("(" + parameters + ");");

		log.info("Dropping function: {}", function.getName());
		execute(connection, queryBuilder);
	}

	private String valueOf(Action action) {
		switch (action) {
			case CASCADE: return "CASCADE";
			case NO_ACTION: return "NO ACTION";
			case RESTRICT: return "RESTRICT";
			case SET_DEFAULT: return "SET DEFAULT";
			case SET_NULL: return "SET NULL";
			default: throw new IllegalArgumentException("Action: " + action + " is not supported!");
		}
	}

	private void execute(Connection connection, QueryBuilder queryBuilder) throws SQLException {
		String query = queryBuilder.toString();
		try (Statement statement = connection.createStatement()) {
			log.debug("Executing: " + query);
			statement.execute(query);
		}
		catch (SQLException e) {
			throw new SQLException(query, e);
		}
	}

}