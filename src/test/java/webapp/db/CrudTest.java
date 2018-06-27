package webapp.db;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import com.vertxexploration.webapp.db.WikiDatabaseService;
import com.vertxexploration.webapp.db.WikiDatabaseVerticle;

import io.vertx.core.AsyncResult;
import io.vertx.core.DeploymentOptions;
import io.vertx.core.Handler;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.unit.Async;
import io.vertx.ext.unit.TestContext;
import io.vertx.ext.unit.junit.VertxUnitRunner;

@RunWith(VertxUnitRunner.class)
public class CrudTest {

	private Vertx vertx; // this is the vertx context
	private WikiDatabaseService service;

	@Before
	public void prepare(TestContext context) throws InterruptedException {
		vertx = Vertx.vertx();

		JsonObject conf = new JsonObject()
				.put(WikiDatabaseVerticle.CONFIG_WIKIDB_JDBC_URL, "jdbc:hsqldb:mem:testdb;shutdown=true") // Just use one-time in-memory db; shutdown after finish
				.put(WikiDatabaseVerticle.CONFIG_WIKIDB_JDBC_MAX_POOL_SIZE, 4);

		vertx.deployVerticle(new WikiDatabaseVerticle(), new DeploymentOptions().setConfig(conf),
				context.asyncAssertSuccess(id -> service = WikiDatabaseService.createProxy(vertx,
						WikiDatabaseVerticle.CONFIG_WIKIDB_QUEUE)));
	}

	@After
	public void finish(TestContext context) {
		vertx.close(context.asyncAssertSuccess());
	}

	@Test /* (timeout=5000) */
	public void async_behavior(TestContext context) {
		// Vertx vertx = Vertx.vertx();
		context.assertEquals("foo", "foo");
		Async a1 = context.async();
		Async a2 = context.async(3);
		vertx.setTimer(100, n -> a1.complete());
		vertx.setPeriodic(100, n -> a2.countDown());
	}

	@Test
	public void crud_operations(TestContext context) {
		Async async = context.async();
		
		// Create a page
		service.createPage("Test", "Some content", context.asyncAssertSuccess(v1 -> {
			
			// Assert the result by fetching that page
			service.fetchPage("Test", context.asyncAssertSuccess(json1 -> {
				context.assertTrue(json1.getBoolean("found")); 
				context.assertTrue(json1.containsKey("id"));
				context.assertEquals("Some content", json1.getString("rawContent")); // Assert the content that is just saved

				// Next test: Updating a page
				service.savePage(json1.getInteger("id"), "Yo!", context.asyncAssertSuccess(v2 -> {

					// Next test: fetch all pages, check page size 
					service.fetchAllPages(context.asyncAssertSuccess(array1 -> {
						context.assertEquals(1, array1.size());

						// Next test: fetch the updated pages, check the content 
						service.fetchPage("Test", context.asyncAssertSuccess(json2 -> {
							context.assertEquals("Yo!", json2.getString("rawContent"));

							// Next test: delete a page
							service.deletePage(json1.getInteger("id"), v3 -> {
								
								// Test by fetching all pages. size should 0 or empty
								service.fetchAllPages(context.asyncAssertSuccess(array2 -> {
									context.assertTrue(array2.isEmpty());
									async.complete();
								}));
							});
						}));
					}));
				}));
			}));
		}));
		async.awaitSuccess(5000);
	}
	
}
