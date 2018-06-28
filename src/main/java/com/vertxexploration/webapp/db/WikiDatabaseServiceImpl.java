package com.vertxexploration.webapp.db;

import java.util.HashMap;
import java.util.List;

import io.reactivex.Flowable;
import io.reactivex.Single;
import io.vertx.core.AsyncResult;
import io.vertx.core.Future;
import io.vertx.core.Handler;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.core.logging.Logger;
import io.vertx.core.logging.LoggerFactory;
import io.vertx.ext.sql.ResultSet;
import io.vertx.reactivex.SingleHelper;
import io.vertx.reactivex.ext.jdbc.JDBCClient;
import io.vertx.reactivex.ext.sql.SQLConnection;

public class WikiDatabaseServiceImpl implements WikiDatabaseService {
	private static final Logger LOGGER = LoggerFactory.getLogger(WikiDatabaseServiceImpl.class);

	private final HashMap<SqlQuery, String> sqlQueries;
	private final JDBCClient dbClient;

	public WikiDatabaseServiceImpl(JDBCClient dbClient, HashMap<SqlQuery, String> sqlQueries,
			Handler<AsyncResult<WikiDatabaseService>> readyHandler) {
		this.dbClient = dbClient;
		this.sqlQueries = sqlQueries;

		getConnection().flatMapCompletable(conn -> {
			return conn.rxExecute(sqlQueries.get(SqlQuery.CREATE_PAGES_TABLE));
		}).andThen(Single.just(this)).subscribe(SingleHelper.toObserver(readyHandler));
	}

	@Override
	public WikiDatabaseService fetchAllPages(Handler<AsyncResult<JsonArray>> resultHandler) {
		dbClient.rxQuery(sqlQueries.get(SqlQuery.ALL_PAGES))
		.flatMapPublisher(res -> {
			List<JsonArray> results = res.getResults();
			return Flowable.fromIterable(results);
		}).map(json -> json.getString(0)).sorted().collect(JsonArray::new, JsonArray::add)
				.subscribe(SingleHelper.toObserver(resultHandler));
		return this;
	}

	@Override
	public WikiDatabaseService fetchPage(String name, Handler<AsyncResult<JsonObject>> resultHandler) {
		dbClient.queryWithParams(sqlQueries.get(SqlQuery.GET_PAGE), new JsonArray().add(name), fetch -> {
			if (fetch.succeeded()) {
				JsonObject response = new JsonObject();
				ResultSet resultSet = fetch.result();
				if (resultSet.getNumRows() == 0) {
					response.put("found", false);
				} else {
					response.put("found", true);
					JsonArray row = resultSet.getResults().get(0);
					response.put("id", row.getInteger(0));
					response.put("rawContent", row.getString(1));
				}
				resultHandler.handle(Future.succeededFuture(response));
			} else {
				LOGGER.error("Database query error", fetch.cause());
				resultHandler.handle(Future.failedFuture(fetch.cause()));
			}
		});
		return this;
	}

	@Override
	public WikiDatabaseService createPage(String title, String markdown, Handler<AsyncResult<Void>> resultHandler) {
		JsonArray data = new JsonArray().add(title).add(markdown);
		dbClient.updateWithParams(sqlQueries.get(SqlQuery.CREATE_PAGE), data, res -> {
			if (res.succeeded()) {
				resultHandler.handle(Future.succeededFuture());
			} else {
				LOGGER.error("Database query error", res.cause());
				resultHandler.handle(Future.failedFuture(res.cause()));
			}
		});
		return this;
	}

	@Override
	public WikiDatabaseService savePage(int id, String markdown, Handler<AsyncResult<Void>> resultHandler) {
		JsonArray data = new JsonArray().add(markdown).add(id);
		dbClient.updateWithParams(sqlQueries.get(SqlQuery.SAVE_PAGE), data, res -> {
			if (res.succeeded()) {
				resultHandler.handle(Future.succeededFuture());
			} else {
				LOGGER.error("Database query error", res.cause());
				resultHandler.handle(Future.failedFuture(res.cause()));
			}
		});
		return this;
	}

	@Override
	public WikiDatabaseService deletePage(int id, Handler<AsyncResult<Void>> resultHandler) {
		JsonArray data = new JsonArray().add(id);
		dbClient.updateWithParams(sqlQueries.get(SqlQuery.DELETE_PAGE), data, res -> {
			if (res.succeeded()) {
				resultHandler.handle(Future.succeededFuture());
			} else {
				LOGGER.error("Database query error", res.cause());
				resultHandler.handle(Future.failedFuture(res.cause()));
			}
		});
		return this;
	}

	@Override
	public WikiDatabaseService fetchAllPagesData(Handler<AsyncResult<List<JsonObject>>> resultHandler) {
		dbClient.query(sqlQueries.get(SqlQuery.ALL_PAGES_DATA), queryResult -> {
			if (queryResult.succeeded()) {
				resultHandler.handle(Future.succeededFuture(queryResult.result().getRows()));
			} else {
				LOGGER.error("Database query error", queryResult.cause());
				resultHandler.handle(Future.failedFuture(queryResult.cause()));
			}
		});
		return this;
	}

	@Override
	public WikiDatabaseService fetchPageById(int id, Handler<AsyncResult<JsonObject>> resultHandler) {
		dbClient.rxQueryWithParams(sqlQueries.get(SqlQuery.GET_PAGE_BY_ID), new JsonArray().add(id)).doOnError(err -> {
			LOGGER.error("Database query error", err);
			resultHandler.handle(Future.failedFuture(err));
			return;
		}).subscribe(res -> {

			if (res.getNumRows() <= 0) {
				resultHandler.handle(Future.succeededFuture(new JsonObject().put("found", false)));
				return;
			}

			JsonObject result = res.getRows().get(0);

			resultHandler.handle(
					Future.succeededFuture(new JsonObject().put("found", true).put("id", result.getInteger("ID"))
							.put("name", result.getString("NAME")).put("content", result.getString("CONTENT"))));
		});
		return this;
	}

	private Single<SQLConnection> getConnection() {
		return dbClient.rxGetConnection().flatMap(conn -> {
			Single<SQLConnection> connectionSingle = Single.just(conn);
			return connectionSingle.doFinally(conn::close); // so after finally,
															// it will be closed
		});
	}
}