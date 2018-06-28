package com.vertxexploration.webapp;

import io.reactivex.Single;
import io.vertx.core.DeploymentOptions;
import io.vertx.core.Future;
import io.vertx.reactivex.core.AbstractVerticle;

public class MainVerticle extends AbstractVerticle {

	@Override
	public void start(Future<Void> startFuture) throws Exception {

		/*
		 * Future<String> dbVerticleDeployment = Future.future();
		 * vertx.deployVerticle(new WikiDatabaseVerticle(),
		 * dbVerticleDeployment.completer());
		 */

		Single<String> dbVerticleDeployment = vertx.rxDeployVerticle("com.vertxexploration.webapp.db.WikiDatabaseVerticle");

		dbVerticleDeployment.flatMap(id -> {

			Single<String> httpVerticleDeployment = vertx.rxDeployVerticle(
					"com.vertxexploration.webapp.http.HttpServerVerticle", new DeploymentOptions().setInstances(2));

			return httpVerticleDeployment;
		}).subscribe(id -> startFuture.complete(), startFuture::fail);

		/*
		 * dbVerticleDeployment.compose(id -> {
		 * 
		 * Future<String> httpVerticleDeployment = Future.future();
		 * vertx.deployVerticle(
		 * "com.vertxexploration.webapp.http.HttpServerVerticle", new
		 * DeploymentOptions().setInstances(2),
		 * httpVerticleDeployment.completer());
		 * 
		 * return httpVerticleDeployment;
		 * 
		 * }).setHandler(ar -> { if (ar.succeeded()) { startFuture.complete(); }
		 * else { startFuture.fail(ar.cause()); } });
		 */
	}
}