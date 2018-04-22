package nl.taallijn.has.http;

import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.github.rjeschke.txtmark.Processor;

import io.vertx.core.AbstractVerticle;
import io.vertx.core.AsyncResult;
import io.vertx.core.Future;
import io.vertx.core.Handler;
import io.vertx.core.http.HttpServer;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.RoutingContext;
import io.vertx.ext.web.client.HttpResponse;
import io.vertx.ext.web.client.WebClient;
import io.vertx.ext.web.client.WebClientOptions;
import io.vertx.ext.web.codec.BodyCodec;
import io.vertx.ext.web.handler.BodyHandler;
import io.vertx.ext.web.templ.FreeMarkerTemplateEngine;
import nl.taallijn.has.database.WikiDatabaseService;

public class HttpServerVerticle extends AbstractVerticle {

	private static final Logger LOGGER = LoggerFactory.getLogger(HttpServerVerticle.class);

	public static final String CONFIG_HTTP_SERVER_PORT = "http.server.port";
	public static final String CONFIG_WIKIDB_QUEUE = "wikidb.queue";

	private final FreeMarkerTemplateEngine templateEngine = FreeMarkerTemplateEngine.create();
	private static final String EMPTY_PAGE_MARKDOWN = "# A new page\n" + "\n" + "Feel-free to write in Markdown!\n";
	private WikiDatabaseService dbService;
	private WebClient webClient;

	@Override
	public void start(Future<Void> startFuture) throws Exception {

		String wikiDbQueue = config().getString(CONFIG_WIKIDB_QUEUE, "wikidb.queue");
		dbService = WikiDatabaseService.createProxy(vertx, wikiDbQueue);
		webClient = WebClient.create(vertx, new WebClientOptions().setSsl(true).setUserAgent("vert-x3"));

		HttpServer server = vertx.createHttpServer();

		Router router = Router.router(vertx);
		router.get("/").handler(this::indexHandler);
		router.get("/backup").handler(this::backupHandler);
		router.get("/wiki/:page").handler(this::pageRenderingHandler);
		router.post().handler(BodyHandler.create());
		router.post("/save").handler(this::pageUpdateHandler);
		router.post("/create").handler(this::pageCreateHandler);
		router.post("/delete").handler(this::pageDeletionHandler);

		Router apiRouter = Router.router(vertx);
		apiRouter.get("/pages").handler(this::apiRoot);
		apiRouter.get("/pages/:id").handler(this::apiGetPage);
		apiRouter.post().handler(BodyHandler.create());
		apiRouter.post("/pages").handler(this::apiCreatePage);
		apiRouter.put().handler(BodyHandler.create());
		apiRouter.put("/pages/:id").handler(this::apiUpdatePage);
		apiRouter.delete("/pages/:id").handler(this::apiDeletePage);
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

	private void apiDeletePage(RoutingContext context) {
		int id = Integer.valueOf(context.request().getParam("id"));
		dbService.deletePage(id, reply -> {
			handleSimpleDbReply(context, reply);
		});
	}

	private void handleSimpleDbReply(RoutingContext context, AsyncResult<Void> reply) {
		if (reply.succeeded()) {
			context.response().setStatusCode(200);
			context.response().putHeader("Content-Type", "application/json");
			context.response().end(new JsonObject().put("success", true).encode());
		} else {
			context.response().setStatusCode(500);
			context.response().putHeader("Content-Type", "application/json");
			context.response()
					.end(new JsonObject().put("success", false).put("error", reply.cause().getMessage()).encode());
		}
	}

	private void apiUpdatePage(RoutingContext context) {
		int id = Integer.valueOf(context.request().getParam("id"));
		JsonObject page = context.getBodyAsJson();
		if (!validateJsonPageDocument(context, page, "markdown")) {
			return;
		}
		dbService.savePage(id, page.getString("markdown"), reply -> {
			handleSimpleDbReply(context, reply);
		});
	}

	private void apiCreatePage(RoutingContext context) {
		JsonObject page = context.getBodyAsJson();
		if (!validateJsonPageDocument(context, page, "name", "markdown")) {
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
		if (!Arrays.stream(expectedKeys).allMatch(page::containsKey)) {
			LOGGER.error("Bad page creation JSON payload: " + page.encodePrettily() + " from "
					+ context.request().remoteAddress());
			context.response().setStatusCode(400);
			context.response().putHeader("Content-Type", "application/json");
			context.response().end(new JsonObject().put("success", false).put("error", "Bad request payload").encode());
			return false;
		}
		return true;
	}

	private void apiGetPage(RoutingContext context) {
		int id = Integer.valueOf(context.request().getParam("id"));
		dbService.fetchPageById(id, reply -> {
			JsonObject response = new JsonObject();
			if (reply.succeeded()) {
				JsonObject dbObject = reply.result();
				if (dbObject.getBoolean("found")) {
					JsonObject payload = new JsonObject().put("name", dbObject.getString("name"))
							.put("id", dbObject.getInteger("id")).put("markdown", dbObject.getString("content"))
							.put("html", Processor.process(dbObject.getString("content")));
					response.put("success", true).put("page", payload);
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

	private void apiRoot(RoutingContext context) {
		dbService.fetchAllPagesData(reply -> {
			JsonObject response = new JsonObject();
			if (reply.succeeded()) {
				List<JsonObject> pages = reply.result().stream()
//						.map(obj -> new JsonObject().put("id", obj.getInteger("ID")).put("name", obj.getString("NAME")))
						.map(obj -> new JsonObject()
								.put("id", obj.getInteger(actualFieldName(obj, "ID")))
								.put("name", obj.getString(actualFieldName(obj, "NAME"))))
						.collect(Collectors.toList());
				response.put("success", true).put("pages", pages);
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

	private void indexHandler(RoutingContext context) {
		dbService.fetchAllPages(reply -> {
			if (reply.succeeded()) {
				context.put("title", "Wiki home");
				context.put("pages", reply.result().getList());
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
	}

	private void backupHandler(RoutingContext context) {
		dbService.fetchAllPagesData(reply -> {
			if (reply.succeeded()) {
				JsonArray filesObject = new JsonArray();
				JsonObject payload = new JsonObject().put("files", filesObject).put("language", "plaintext")
						.put("title", "vertx-wiki-backup").put("public", true);
				reply.result().forEach(page -> {
					JsonObject fileObject = new JsonObject();
					fileObject.put("name", page.getString(actualFieldName(page, "NAME")));
					fileObject.put("content", page.getString(actualFieldName(page, "CONTENT")));
					filesObject.add(fileObject);
				});
				webClient.post(443, "snippets.glot.io", "/snippets").putHeader("Content-Type", "application/json")
						.as(BodyCodec.jsonObject()).sendJsonObject(payload, ar -> {
							if (ar.succeeded()) {
								HttpResponse<JsonObject> response = ar.result();
								if (response.statusCode() == 200) {
									String url = "https://glot.io/snippets/" + response.body().getString("id");
									context.put("backup_gist_url", url);
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

	private String actualFieldName(JsonObject jsonObject, String normalisedFieldName) {
		LOGGER.debug("normalised page field name = {}", normalisedFieldName);
		String actual = jsonObject.fieldNames().stream().filter(name -> name.toUpperCase().equals(normalisedFieldName)).findFirst().get();
		LOGGER.debug("actual page field name = {}", actual);
		return actual; 
	}

//	private String actualFieldName(JsonObject page, String string) {
//		return page.fieldNames().stream().filter(name -> name.toUpperCase().equals(string)).findFirst().get();
//	}

	private void pageRenderingHandler(RoutingContext context) {
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
			dbService.createPage(title, markdown, handler);
		} else {
			dbService.savePage(Integer.valueOf(context.request().getParam("id")), markdown, handler);
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
		dbService.deletePage(Integer.valueOf(context.request().getParam("id")), reply -> {
			if (reply.succeeded()) {
				context.response().setStatusCode(303);
				context.response().putHeader("Location", "/");
				context.response().end();
			} else {
				context.fail(reply.cause());
			}
		});
	}

}
