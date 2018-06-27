package webapp.api;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import com.vertxexploration.webapp.http.HttpServerVerticle;

import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.logging.Logger;
import io.vertx.core.logging.LoggerFactory;
import io.vertx.ext.unit.Async;
import io.vertx.ext.unit.TestContext;
import io.vertx.ext.unit.junit.VertxUnitRunner;
import io.vertx.ext.web.client.HttpResponse;
import io.vertx.ext.web.client.WebClient;
import io.vertx.ext.web.client.WebClientOptions;

@RunWith(VertxUnitRunner.class)
public class ApiClientTest {
	private Vertx vertx; // this is the vertx context

	private static final Logger LOGGER = LoggerFactory.getLogger(ApiClientTest.class);
	
	@Before
	public void prepare(TestContext context) throws InterruptedException {
		vertx = Vertx.vertx();
	}

	@After
	public void finish(TestContext context) {
		vertx.close(context.asyncAssertSuccess());
	}

	@Test
	public void start_http_server(TestContext context) {
		Async async = context.async();

		vertx.createHttpServer().requestHandler(req -> req.response().putHeader("Content-Type", "text/plain").end("Ok"))
			.listen(8080, context.asyncAssertSuccess(server -> {

				WebClient webClient = WebClient.create(vertx);

				webClient.get(8080, "localhost", "/").send(ar -> {
					if (ar.succeeded()) {
						HttpResponse<Buffer> response = ar.result();
						context.assertTrue(response.headers().contains("Content-Type"));
						context.assertEquals("text/plain", response.getHeader("Content-Type"));
						context.assertEquals("Ok", response.body().toString());
						webClient.close();
						async.complete();
					} else {
						LOGGER.error(ar.cause());
						async.resolve(Future.failedFuture(ar.cause()));
					}
				});
			}));

		//async.awaitSuccess(5000);
	}
}
