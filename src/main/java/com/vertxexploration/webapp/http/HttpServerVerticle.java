package com.vertxexploration.webapp.http;

import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.github.rjeschke.txtmark.Processor;
import com.vertxexploration.webapp.db.WikiDatabaseService;

import io.vertx.core.AsyncResult;
import io.vertx.core.Future;
import io.vertx.core.Handler;
import io.vertx.core.http.HttpServerOptions;
import io.vertx.core.json.JsonObject;
import io.vertx.core.net.JksOptions;
import io.vertx.ext.auth.KeyStoreOptions;
import io.vertx.ext.auth.jwt.JWTAuthOptions;
import io.vertx.ext.auth.jwt.JWTOptions;
import io.vertx.ext.auth.shiro.ShiroAuthOptions;
import io.vertx.ext.auth.shiro.ShiroAuthRealmType;
import io.vertx.ext.web.client.WebClientOptions;
import io.vertx.reactivex.core.AbstractVerticle;
import io.vertx.reactivex.core.http.HttpServer;
import io.vertx.reactivex.ext.auth.AuthProvider;
import io.vertx.reactivex.ext.auth.User;
import io.vertx.reactivex.ext.auth.jwt.JWTAuth;
import io.vertx.reactivex.ext.auth.shiro.ShiroAuth;
import io.vertx.reactivex.ext.web.Router;
import io.vertx.reactivex.ext.web.RoutingContext;
import io.vertx.reactivex.ext.web.client.HttpResponse;
import io.vertx.reactivex.ext.web.client.WebClient;
import io.vertx.reactivex.ext.web.codec.BodyCodec;
import io.vertx.reactivex.ext.web.handler.AuthHandler;
import io.vertx.reactivex.ext.web.handler.BodyHandler;
import io.vertx.reactivex.ext.web.handler.CookieHandler;
import io.vertx.reactivex.ext.web.handler.FormLoginHandler;
import io.vertx.reactivex.ext.web.handler.JWTAuthHandler;
import io.vertx.reactivex.ext.web.handler.RedirectAuthHandler;
import io.vertx.reactivex.ext.web.handler.SessionHandler;
import io.vertx.reactivex.ext.web.handler.UserSessionHandler;
import io.vertx.reactivex.ext.web.sstore.LocalSessionStore;
import io.vertx.reactivex.ext.web.templ.FreeMarkerTemplateEngine;

public class HttpServerVerticle extends AbstractVerticle {

	private static final Logger LOGGER = LoggerFactory.getLogger(HttpServerVerticle.class);

	public static final String CONFIG_HTTP_SERVER_PORT = "http.server.port";
	public static final String CONFIG_WIKIDB_QUEUE = "wikidb.queue";
	private static final String EMPTY_PAGE_MARKDOWN = "# A new page\n" + "\n" + "Feel-free to write in Markdown!\n";

	private String wikiDbQueue = "wikidb.queue";

	// For auto redeployment for code changes, put --redeploy="src/**/*.java"
	// --launcher-class=io.vertx.core.Launcher to Program argument
	private WikiDatabaseService dbService;
	private AuthProvider auth;
	private JWTAuth jwtAuth;

	@Override
	public void start(Future<Void> startFuture) throws Exception {

		String wikiDbQueue = config().getString(CONFIG_WIKIDB_QUEUE, "wikidb.queue");
		dbService = WikiDatabaseService.createProxy(vertx.getDelegate(), wikiDbQueue);

		this.auth = ShiroAuth.create(vertx, new ShiroAuthOptions()
				.setType(ShiroAuthRealmType.PROPERTIES)
				.setConfig(new JsonObject()
						.put("properties_path", "classpath:wiki-users.properties")));
		
		HttpServer server = vertx.createHttpServer(new HttpServerOptions()
			.setSsl(true)
			.setKeyStoreOptions(new JksOptions()
				.setPath("server-keystore.jks")
				.setPassword("secret")));

		Router router = Router.router(vertx);
		
		// Security config
		router.route().handler(CookieHandler.create());
		router.route().handler(BodyHandler.create());
		router.route().handler(SessionHandler.create(LocalSessionStore.create(vertx)));
		router.route().handler(UserSessionHandler.create(auth));  

		AuthHandler authHandler = RedirectAuthHandler.create(auth, "/login"); 
		router.route("/").handler(authHandler);  
		router.route("/wiki/*").handler(authHandler);
		router.route("/action/*").handler(authHandler);
		
		// login/logout
		router.get("/login").handler(this::loginHandler);
		router.post("/login-auth").handler(FormLoginHandler.create(auth));  
		router.get("/logout").handler(context -> {
		  context.clearUser();  
		  context.response()
		    .setStatusCode(302)
		    .putHeader("Location", "/")
		    .end();
		});
		
		// Website gateway
		router.get("/").handler(this::indexHandler);
		router.get("/wiki/:page").handler(this::pageRenderingHandler);
		router.post().handler(BodyHandler.create());
		router.post("/action/save").handler(this::pageUpdateHandler);
		router.post("/action/create").handler(this::pageCreateHandler);
		router.post("/action/delete").handler(this::pageDeletionHandler);
		router.get("/action/backup").handler(this::backupHandler);

		
		// API Gateway
		Router apiRouter = Router.router(vertx);
		

		this.jwtAuth = JWTAuth.create(vertx, 
			new JWTAuthOptions().setKeyStore(
				new KeyStoreOptions()
				.setPath("keystore.jceks")
				.setPassword("secret")));

		apiRouter.route().handler(JWTAuthHandler.create(jwtAuth, "/api/token"));
		
		apiRouter.get("/pages").handler(this::apiRoot);
		apiRouter.get("/pages/:id").handler(this::apiGetPage);
		apiRouter.post().handler(BodyHandler.create());
		apiRouter.post("/pages").handler(this::apiCreatePage);
		apiRouter.put().handler(BodyHandler.create());
		apiRouter.put("/pages/:id").handler(this::apiUpdatePage);
		apiRouter.delete("/pages/:id").handler(this::apiDeletePage);
		apiRouter.get("/token").handler(this::jwtTokenGeneratorHandler);
		
		router.mountSubRouter("/api", apiRouter);

		int portNumber = config().getInteger(CONFIG_HTTP_SERVER_PORT, 8080);
		server.requestHandler(router::accept).listen(portNumber, ar -> {
			if (ar.succeeded()) {
				LOGGER.info("HTTP server running on port " + portNumber);
				startFuture.complete();
			} else {
				LOGGER.error("Could not start a HTTP server", ar.cause());
				startFuture.fail(ar.cause());
			}
		});
	}
	
	private void jwtTokenGeneratorHandler(RoutingContext context) {
		JsonObject creds = new JsonObject()
				.put("username", context.request().getHeader("login"))
				.put("password", context.request().getHeader("password"));
		
		auth.authenticate(creds, authResult -> { // this is to authenticate the user id

			if (authResult.succeeded()) {
				
				// From here above, it is to authorize the user based on wiki-user.properties
				User user = authResult.result();
				user.isAuthorised("create", canCreate -> {
					user.isAuthorised("delete", canDelete -> {
						user.isAuthorised("update", canUpdate -> {

							String token = this.jwtAuth.generateToken(
									new JsonObject().put("username", context.request().getHeader("login"))
											.put("canCreate", canCreate.succeeded() && canCreate.result())
											.put("canDelete", canDelete.succeeded() && canDelete.result())
											.put("canUpdate", canUpdate.succeeded() && canUpdate.result()),
									new JWTOptions().setSubject("Wiki API").setIssuer("Vert.x"));
							context.response().putHeader("Content-Type", "text/plain").end(token);
						});
					});
				});
			} else {
				context.fail(401);
			}
		});
	}
	
	private void loginHandler(RoutingContext context) {
		context.put("title", "Login");
		templateEngine.render(context, "templates", "/login.ftl", ar -> {
			if (ar.succeeded()) {
				context.response().putHeader("Content-Type", "text/html");
				context.response().end(ar.result());
			} else {
				context.fail(ar.cause());
			}
		});
	}

	private final FreeMarkerTemplateEngine templateEngine = FreeMarkerTemplateEngine.create();

	private void indexHandler(RoutingContext context) {
		context.user().isAuthorised("create", res -> {
			boolean canCreatePage = res.succeeded() && res.result();
			dbService.fetchAllPages(reply -> {
				if (reply.succeeded()) {
					context.put("title", "Wiki home");
					context.put("pages", reply.result().getList());
					context.put("canCreatePage", canCreatePage); // to be used in the index.ftl template
					context.put("username", context.user().principal().getString("username"));
					templateEngine.render(context, "templates", "/index.ftl", ar -> {
						if (ar.succeeded()) {
							context.response().putHeader("Content-Type", "text/html");
							context.response().end(ar.result());
						} else {
							context.fail(ar.cause());
						}
					});
				} else {
					context.fail(reply.cause());
				}
			});
		});
	}

	private void pageRenderingHandler(RoutingContext context) {
		context.user().isAuthorized("update", updateResponse -> {
			boolean canSavePage = updateResponse.succeeded() && updateResponse.result();
			context.user().isAuthorized("delete", deleteResponse -> {
				boolean canDeletePage = deleteResponse.succeeded() && deleteResponse.result();

				String requestedPage = context.request().getParam("page");
				dbService.fetchPage(requestedPage, reply -> {
					if (reply.succeeded()) {

						JsonObject payLoad = reply.result();
						boolean found = payLoad.getBoolean("found");
						String rawContent = payLoad.getString("rawContent", EMPTY_PAGE_MARKDOWN);
						context.put("title", requestedPage);
						context.put("id", payLoad.getInteger("id", -1));
						context.put("newPage", found ? "no" : "yes");
						context.put("rawContent", rawContent);
						context.put("content", Processor.process(rawContent));
						context.put("timestamp", new Date().toString());
						context.put("username", context.user().principal().getString("username"));
						context.put("canSavePage", canSavePage);
						context.put("canDeletePage", canDeletePage);

						templateEngine.render(context, "templates", "/page.ftl", ar -> {
							if (ar.succeeded()) {
								context.response().putHeader("Content-Type", "text/html");
								context.response().end(ar.result());
							} else {
								context.fail(ar.cause());
							}
						});

					} else {
						context.fail(reply.cause());
					}
				});

			});
		});
	}

	private void pageUpdateHandler(RoutingContext context) {
		String title = context.request().getParam("title");

		Handler<AsyncResult<Void>> handler = reply -> {
			if (reply.succeeded()) {
				context.response().setStatusCode(303);
				context.response().putHeader("Location", "/wiki/" + title);
				context.response().end();
			} else {
				context.fail(reply.cause());
			}
		};

		String markdown = context.request().getParam("markdown");
		if ("yes".equals(context.request().getParam("newPage"))) {
			context.user().isAuthorised("create", res -> {
				if (res.succeeded() && res.result()) {
					dbService.createPage(title, markdown, handler);
				} else {
					context.response().setStatusCode(403).end();
				}
			});
		} else {
			context.user().isAuthorised("update", res -> {
				if (res.succeeded() && res.result()) {
					dbService.savePage(Integer.valueOf(context.request().getParam("id")), markdown, handler);
				} else {
					context.response().setStatusCode(403).end();
				} 
			});
		}
	}

	private void pageCreateHandler(RoutingContext context) {
		String pageName = context.request().getParam("name");
		String location = "/wiki/" + pageName;
		if (pageName == null || pageName.isEmpty()) {
			location = "/";
		}
		context.response().setStatusCode(303);
		context.response().putHeader("Location", location);
		context.response().end();
	}

	private void pageDeletionHandler(RoutingContext context) {
		context.user().isAuthorised("delete", res -> {
			if (res.succeeded() && res.result()) {

				// Original code:
				dbService.deletePage(Integer.valueOf(context.request().getParam("id")), reply -> {
					if (reply.succeeded()) {
						context.response().setStatusCode(303);
						context.response().putHeader("Location", "/");
						context.response().end();
					} else {
						context.fail(reply.cause());
					}
				});

			} else {
				context.response().setStatusCode(403).end();
			}
		});
	}

	private void backupHandler(RoutingContext context) {
		WebClient webClient = WebClient.create(vertx,
				new WebClientOptions().setSsl(true).setUserAgent("joshuahendinata"));

		dbService.fetchAllPagesData(reply -> {
			if (reply.succeeded()) {

				JsonObject filesObject = new JsonObject();
				JsonObject gistPayload = new JsonObject().put("files", filesObject).put("description", "A wiki backup")
						.put("public", true);

				reply.result().forEach(page -> {
					JsonObject fileObject = new JsonObject();
					filesObject.put(page.getString("NAME"), fileObject);
					fileObject.put("content", page.getString("CONTENT"));
				});

				webClient.post(443, "api.github.com", "/gists").putHeader("Accept", "application/vnd.github.v3+json")
					.putHeader("Content-Type", "application/json").as(BodyCodec.jsonObject())
					.sendJsonObject(gistPayload, ar -> { // This code will call all the way down until netty server implementation
						if (ar.succeeded()) {
							HttpResponse<JsonObject> response = ar.result();
							if (response.statusCode() == 201) {
								context.put("backup_gist_url", response.body().getString("html_url"));
								indexHandler(context);
							} else {
								StringBuilder message = new StringBuilder().append("Could not backup the wiki: ")
										.append(response.statusMessage());
								JsonObject body = response.body();
								if (body != null) {
									message.append(System.getProperty("line.separator"))
											.append(body.encodePrettily());
								}
								LOGGER.error(message.toString());
								context.fail(502);
							}
						} else {
							Throwable err = ar.cause();
							LOGGER.error("HTTP Client error", err);
							context.fail(err);
						}
					});

			} else {
				context.fail(reply.cause());
			}
		});
	}

	private void apiRoot(RoutingContext context) {
		dbService.fetchAllPagesData(reply -> {
			JsonObject response = new JsonObject();
			if (reply.succeeded()) {
				List<JsonObject> pages = reply.result()
					.stream() // stream the result
					.map(obj -> new JsonObject() // similar to "For each", transform the result to JSON object
							.put("id", obj.getInteger("ID"))
							.put("name", obj.getString("NAME")))
					.collect(Collectors.toList()); // Combine it into List
				response.put("success", true)
						.put("pages", pages);
				context.response().setStatusCode(200);
				context.response().putHeader("Content-Type", "application/json");
				context.response().end(response.encode());
			} else {
				response.put("success", false).put("error", reply.cause().getMessage());
				context.response().setStatusCode(500);
				context.response().putHeader("Content-Type", "application/json");
				context.response().end(response.encode());
			}
		});
	}
	
	private void apiGetPage(RoutingContext context) {
		int id = Integer.valueOf(context.request().getParam("id"));
		
		dbService.fetchPageById(id, reply -> {
			JsonObject response = new JsonObject();
			if (reply.succeeded()) {
				JsonObject dbObject = reply.result();
				if (dbObject.getBoolean("found")) {
					JsonObject payload = new JsonObject()
							.put("name", dbObject.getString("name"))
							.put("id", dbObject.getInteger("id")).put("markdown", dbObject.getString("content"))
							.put("html", Processor.process(dbObject.getString("content")));
					response.put("success", true)
							.put("page", payload);
					context.response().setStatusCode(200);
				} else {
					context.response().setStatusCode(404);
					response.put("success", false).put("error", "There is no page with ID " + id);
				}
			} else {
				response.put("success", false).put("error", reply.cause().getMessage());
				context.response().setStatusCode(500);
			}
			context.response().putHeader("Content-Type", "application/json");
			context.response().end(response.encode());
		});
	}
	
	private void apiCreatePage(RoutingContext context) {
		JsonObject page = context.getBodyAsJson();
		if (!validateJsonPageDocument(context, page, "name", "markdown")) {
			context.response().setStatusCode(400);
			context.response().putHeader("Content-Type", "application/json");
			context.response().end(new JsonObject()
					.put("success", false)
					.put("error", "Bad request payload")
					.encode());
			return;
		}
		
		dbService.createPage(page.getString("name"), page.getString("markdown"), reply -> {
			if (reply.succeeded()) {
				context.response().setStatusCode(201);
				context.response().putHeader("Content-Type", "application/json");
				context.response().end(new JsonObject().put("success", true).encode());
			} else {
				context.response().setStatusCode(500);
				context.response().putHeader("Content-Type", "application/json");
				context.response()
						.end(new JsonObject().put("success", false).put("error", reply.cause().getMessage()).encode());
			}
		});
	}
	
	private boolean validateJsonPageDocument(RoutingContext context, JsonObject page, String... expectedKeys) {
		if (!Arrays.stream(expectedKeys).allMatch(page::containsKey)) { // check if the provided page has all the expected keys
			LOGGER.error("Bad page creation JSON payload: " + page.encodePrettily() + " from "
					+ context.request().remoteAddress());
			return false;
		}
		return true;
	}
	
	private void apiUpdatePage(RoutingContext context) {
		int id = Integer.valueOf(context.request().getParam("id"));
		JsonObject page = context.getBodyAsJson();
		if (!validateJsonPageDocument(context, page, "markdown")) {
			context.response().setStatusCode(400);
			context.response().putHeader("Content-Type", "application/json");
			context.response().end(new JsonObject()
					.put("success", false)
					.put("error", "Bad request payload")
					.encode());
			return;
		}
		dbService.savePage(id, page.getString("markdown"), reply -> {
			handleSimpleDbReply(context, reply);
		});
	}
	
	private void handleSimpleDbReply(RoutingContext context, AsyncResult<Void> reply) {
		if (reply.succeeded()) {
			context.response().setStatusCode(200);
			context.response().putHeader("Content-Type", "application/json");
			context.response().end(new JsonObject()
					.put("success", true)
					.encode());
		} else {
			context.response().setStatusCode(500);
			context.response().putHeader("Content-Type", "application/json");
			context.response().end(new JsonObject()
									.put("success", false)
									.put("error", reply.cause().getMessage())
									.encode());
		}
	}
	
	private void apiDeletePage(RoutingContext context) {
		if (context.user().principal().getBoolean("canDelete", false)) {
			int id = Integer.valueOf(context.request().getParam("id"));
			dbService.deletePage(id, reply -> {
				handleSimpleDbReply(context, reply);
			});
		} else {
			context.fail(401);
		}
	}
}
