package io.quantumdb.core.backends.postgresql;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import com.google.common.base.Joiner;
import com.google.common.collect.Lists;
import io.quantumdb.core.backends.DatabaseMigrator;
import io.quantumdb.core.backends.postgresql.migrator.Expansion;
import io.quantumdb.core.backends.postgresql.migrator.NullRecordCreator;
import io.quantumdb.core.backends.postgresql.migrator.TableCreator;
import io.quantumdb.core.backends.postgresql.planner.ExpansiveMigrationPlanner;
import io.quantumdb.core.backends.postgresql.planner.MigrationPlan;
import io.quantumdb.core.migration.utils.DataMapping;
import io.quantumdb.core.migration.utils.DataMappings;
import io.quantumdb.core.schema.definitions.Catalog;
import io.quantumdb.core.schema.definitions.Table;
import io.quantumdb.core.utils.QueryBuilder;
import io.quantumdb.core.utils.RandomHasher;
import io.quantumdb.core.versioning.State;
import io.quantumdb.core.versioning.Version;
import lombok.extern.slf4j.Slf4j;

@Slf4j
class PostgresqlMigrator implements DatabaseMigrator {

	private final PostgresqlBackend backend;
	private final NullRecordCreator nullRecordCreator;

	PostgresqlMigrator(PostgresqlBackend backend) {
		this.backend = backend;
		this.nullRecordCreator = new NullRecordCreator();
	}

	public void migrate(State state, Version from, Version to) throws MigrationException {
		Expansion expansion;

		try (Connection connection = backend.connect()) {
			expansion = expand(connection, state, from, to);
			nullRecordCreator.insertNullObjects(connection, expansion.getCreateNullObjectsForTables().stream()
					.map(expansion.getState().getCatalog()::getTable)
					.collect(Collectors.toList()));

			synchronizeForwards(connection, expansion);
		}
		catch (SQLException e) {
			throw new MigrationException(e);
		}

		try {
			migrateBaseData(expansion);
//		    migrateRelationalData(expansion);
		}
		catch (SQLException | InterruptedException e) {
			throw new MigrationException(e);
		}

		try (Connection connection = backend.connect()) {
			removeNullObjects(connection, expansion);
			synchronizeBackwards(connection, expansion);
		}
		catch (SQLException e) {
			throw new MigrationException(e);
		}
	}

	private Expansion expand(Connection connection, State state, Version from, Version to) throws SQLException {
		MigrationPlan plan = new ExpansiveMigrationPlanner().createPlan(state, from, to);

		Catalog catalog = state.getCatalog();
		Expansion expansion = new Expansion(plan, state);

		Set<Table> newTables = expansion.getTableIds().stream()
				.map(catalog::getTable)
				.collect(Collectors.toSet());

		createTables(connection, newTables);
		return expansion;
	}

	private void synchronizeForwards(Connection connection, Expansion expansion) throws SQLException {
		for (DataMapping dataMapping : listDataMappings(expansion, DataMappings.Direction.FORWARDS)) {
			installDataMapping(connection, dataMapping);
		}
	}

	private void migrateBaseData(Expansion expansion) throws SQLException, InterruptedException {
		for (DataMapping dataMapping : listDataMappings(expansion, DataMappings.Direction.FORWARDS)) {
			new TableDataMigrator(backend, dataMapping).migrateData();
		}
	}

	private void migrateRelationalData(Expansion expansion) throws SQLException, InterruptedException {
		for (DataMapping dataMapping : listDataMappings(expansion, DataMappings.Direction.FORWARDS)) {
//			new TableDataMigrator(backend, dataMapping).migrateData();
		}
	}

	private void removeNullObjects(Connection connection, Expansion expansion) {

	}

	private void synchronizeBackwards(Connection connection, Expansion expansion) throws SQLException {
		for (DataMapping dataMapping : listDataMappings(expansion, DataMappings.Direction.BACKWARDS)) {
			installDataMapping(connection, dataMapping);
		}
	}

	private List<DataMapping> listDataMappings(Expansion expansion, DataMappings.Direction direction) {
		State state = expansion.getState();
		Catalog catalog = state.getCatalog();
		DataMappings dataMappings = expansion.getDataMappings();

		List<DataMapping> results = Lists.newArrayList();
		for (String tableId : expansion.getTableIds()) {
			Table table = catalog.getTable(tableId);
			Set<DataMapping> mappings = dataMappings.getTransitiveDataMappings(table, direction);
			results.addAll(mappings);
		}

		return results;
	}

	private void createTables(Connection connection, Collection<Table> tables) throws SQLException {
		new TableCreator().create(connection, tables);
	}

	private void installDataMapping(Connection connection, DataMapping dataMapping) throws SQLException {
		Table sourceTable = dataMapping.getSourceTable();
		String sourceTableName = sourceTable.getName();

		List<String> sourceColumnNames = Lists.newArrayList(dataMapping.getColumnMappings().keySet());

		List<String> targetColumnNames = sourceColumnNames.stream()
				.map(dataMapping.getColumnMappings()::get)
				.map(DataMapping.ColumnMapping::getColumnName)
				.collect(Collectors.toList());

		List<String> prefixedSourceColumnNames = sourceColumnNames.stream()
				.map(columnName -> "NEW." + columnName)
				.collect(Collectors.toList());

		String setClause = dataMapping.getColumnMappings().entrySet().stream()
				.map(entry -> entry.getValue().getColumnName() + " = NEW." + entry.getKey())
				.reduce((l, r) -> l + ", " + r)
				.orElseThrow(() -> new IllegalArgumentException("Cannot map 0 columns!"));

		String prefixedIdClass = dataMapping.getTargetTable().getIdentityColumns().stream()
				.map(column -> column.getName() + " = " + prefixedSourceColumnNames.get(targetColumnNames.indexOf(column.getName())))
				.reduce((l, r) -> l + " AND " + r)
				.orElseThrow(() -> new IllegalArgumentException("Cannot update table without identity columns!"));

		String idClausePrefixWithNew = dataMapping.getTargetTable().getIdentityColumns().stream()
				.map(column -> column.getName() + " = OLD." + sourceColumnNames.get(targetColumnNames.indexOf(column.getName())))
				.reduce((l, r) -> l + " AND " + r)
				.orElseThrow(() -> new IllegalArgumentException("Cannot update table without identity columns!"));

		String functionName = "sync_" + RandomHasher.generateHash();
		QueryBuilder functionBuilder = new QueryBuilder()
				.append("CREATE FUNCTION " + functionName + "()")
				.append("RETURNS TRIGGER AS $$")
				.append("BEGIN")
				.append("   IF TG_OP = 'INSERT' THEN")
				.append("       LOOP")
				.append("           UPDATE " + dataMapping.getTargetTable().getName())
				.append("               SET " + setClause)
				.append("               WHERE " + prefixedIdClass + ";")
				.append("           IF found THEN EXIT; END IF;")
				.append("           BEGIN")
				.append("               INSERT INTO " + dataMapping.getTargetTable().getName())
				.append("                   (" + Joiner.on(", ").join(targetColumnNames) + ") VALUES")
				.append("                   (" + Joiner.on(", ").join(prefixedSourceColumnNames) + ");")
				.append("               EXIT;")
				.append("           EXCEPTION WHEN unique_violation THEN")
				.append("           END;")
				.append("       END LOOP;")
				.append("   ELSIF TG_OP = 'UPDATE' THEN")
				.append("       LOOP")
				.append("           UPDATE " + dataMapping.getTargetTable().getName())
				.append("               SET " + setClause)
				.append("               WHERE " + prefixedIdClass + ";")
				.append("           IF found THEN EXIT; END IF;")
				.append("           BEGIN")
				.append("               INSERT INTO " + dataMapping.getTargetTable().getName())
				.append("                   (" + Joiner.on(", ").join(targetColumnNames) + ") VALUES")
				.append("                   (" + Joiner.on(", ").join(prefixedSourceColumnNames) + ");")
				.append("               EXIT;")
				.append("           EXCEPTION WHEN unique_violation THEN")
				.append("           END;")
				.append("       END LOOP;")
				.append("   ELSIF TG_OP = 'DELETE' THEN")
				.append("       DELETE FROM " + dataMapping.getTargetTable().getName())
				.append("           WHERE " + idClausePrefixWithNew + ";")
				.append("   END IF;")
				.append("   RETURN NEW;")
				.append("END;")
				.append("$$ LANGUAGE 'plpgsql';");

		String triggerName = "sync_" + RandomHasher.generateHash();
		QueryBuilder triggerBuilder = new QueryBuilder()
				.append("CREATE TRIGGER " + triggerName)
				.append("AFTER INSERT OR UPDATE OR DELETE")
				.append("ON " + sourceTable.getName())
				.append("FOR EACH ROW")
				.append("WHEN (pg_trigger_depth() = 0)")
				.append("EXECUTE PROCEDURE " + functionName + "();");

		log.info("Creating sync function: {} for table: {}", functionName, sourceTableName);
		execute(connection, functionBuilder);

		log.info("Creating trigger: {} for table: {}", triggerName, sourceTableName);
		execute(connection, triggerBuilder);
	}

	private void execute(Connection connection, QueryBuilder queryBuilder) throws SQLException {
		String query = queryBuilder.toString();
		try (Statement statement = connection.createStatement()) {
			log.debug("Executing: " + query);
			statement.execute(query);
		}
	}

}
