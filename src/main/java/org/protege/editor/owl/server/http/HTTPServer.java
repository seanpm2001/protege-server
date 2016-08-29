package org.protege.editor.owl.server.http;

import edu.stanford.protege.metaproject.ConfigurationManager;
import edu.stanford.protege.metaproject.api.AuthToken;
import edu.stanford.protege.metaproject.api.ServerConfiguration;
import edu.stanford.protege.metaproject.api.exception.ObjectConversionException;
import io.undertow.Handlers;
import io.undertow.Undertow;
import io.undertow.UndertowOptions;
import io.undertow.server.RoutingHandler;
import io.undertow.server.handlers.BlockingHandler;
import io.undertow.server.handlers.GracefulShutdownHandler;
import io.undertow.util.StatusCodes;
import org.protege.editor.owl.server.api.LoginService;
import org.protege.editor.owl.server.base.ProtegeServer;
import org.protege.editor.owl.server.http.exception.ServerConfigurationInitializationException;
import org.protege.editor.owl.server.http.exception.ServerException;
import org.protege.editor.owl.server.http.handlers.*;
import org.protege.editor.owl.server.security.SSLContextFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.Nonnull;
import javax.net.ssl.SSLContext;
import java.io.File;
import java.io.FileNotFoundException;
import java.net.URI;

public final class HTTPServer {

	public static final String SERVER_CONFIGURATION_PROPERTY = "org.protege.owl.server.configuration";

	public static final String DEFAULT_HOST = "localhost";
	public static final int DEFAULT_PORT = 8080;

	private static Logger logger = LoggerFactory.getLogger(HTTPServer.class);

	private final String configurationFilePath;

	private final TokenTable loginTokenTable;

	private ServerConfiguration serverConfiguration;

	private Undertow webServer;
	private Undertow adminServer;

	private GracefulShutdownHandler webRouterHandler;
	private GracefulShutdownHandler adminRouterHandler;

	private boolean isRunning = false;

	private static HTTPServer server;

	/**
	 * Default constructor
	 */
	public HTTPServer() throws Exception {
		this(System.getProperty(SERVER_CONFIGURATION_PROPERTY));
	}

	/**
	 * HTTP server constructor.
	 *
	 * @param configurationFilePath
	 *			The location of the server configuration file.
	 */
	public HTTPServer(@Nonnull String configurationFilePath) throws Exception {
		this.configurationFilePath = configurationFilePath;
		loadConfig(configurationFilePath);
		loginTokenTable = createLoginTokenTable();
		server = this;
	}

	public static HTTPServer server() {
		return server;
	}

	public void addSession(String key, AuthToken tok) {
		loginTokenTable.put(key, tok);
	}

	public AuthToken getAuthToken(String tok) throws ServerException {
		return loginTokenTable.get(tok);
	}

	private void loadConfig(String filePath) throws ServerConfigurationInitializationException {
		try {
			serverConfiguration = ConfigurationManager.getConfigurationLoader()
					.loadConfiguration(new File(filePath));
		}
		catch (FileNotFoundException | ObjectConversionException e) {
			logger.error("Unable to load server configuration at location: " + filePath, e);
			throw new ServerConfigurationInitializationException("Unable to load server configuration", e);
		}
	}

	private void reloadConfig() throws ServerConfigurationInitializationException {
		loadConfig(configurationFilePath);
	}

	public void start() throws Exception {
		final ProtegeServer pserver = new ProtegeServer(serverConfiguration);
		final URI serverHostUri = serverConfiguration.getHost().getUri();
		final int serverAdminPort = serverConfiguration.getHost().getSecondaryPort().get().get();
		
		RoutingHandler webRouter = Handlers.routing();
		RoutingHandler adminRouter = Handlers.routing();
		
		// create login handler
		BlockingHandler login_handler = loadAndCreateLogin(serverConfiguration);
		
		webRouter.add("POST", ServerEndpoints.LOGIN, login_handler);
		adminRouter.add("POST", ServerEndpoints.LOGIN, login_handler);
		
		// create change service handler
		AuthenticationHandler changeServiceHandler = new AuthenticationHandler(new BlockingHandler(new HTTPChangeService(pserver)));
		webRouter.add("POST", ServerEndpoints.COMMIT,  changeServiceHandler);
		webRouter.add("POST", ServerEndpoints.HEAD,  changeServiceHandler);
		webRouter.add("POST", ServerEndpoints.LATEST_CHANGES,  changeServiceHandler);
		webRouter.add("POST", ServerEndpoints.ALL_CHANGES,  changeServiceHandler);
		
		// create code generator handler
		AuthenticationHandler codeGenHandler = new AuthenticationHandler(new BlockingHandler(new CodeGenHandler(pserver)));
		webRouter.add("GET", ServerEndpoints.GEN_CODE, codeGenHandler);
		webRouter.add("GET", ServerEndpoints.GEN_CODES, codeGenHandler);
		webRouter.add("POST", ServerEndpoints.EVS_REC, codeGenHandler);
		
		// create mataproject handler
		AuthenticationHandler metaprojectHandler = new AuthenticationHandler(new BlockingHandler(new MetaprojectHandler(pserver)));
		webRouter.add("GET", ServerEndpoints.METAPROJECT, metaprojectHandler);
		webRouter.add("GET", ServerEndpoints.PROJECT,  metaprojectHandler);
		webRouter.add("POST", ServerEndpoints.PROJECT_SNAPSHOT_GET,  metaprojectHandler);
		webRouter.add("GET", ServerEndpoints.PROJECTS, metaprojectHandler);
		adminRouter.add("GET", ServerEndpoints.METAPROJECT, metaprojectHandler);
		adminRouter.add("POST", ServerEndpoints.METAPROJECT, metaprojectHandler);
		adminRouter.add("POST", ServerEndpoints.PROJECT,  metaprojectHandler);
		adminRouter.add("POST", ServerEndpoints.PROJECT_SNAPSHOT,  metaprojectHandler);
		adminRouter.add("DELETE", ServerEndpoints.PROJECT,  metaprojectHandler);
		
		// create server handler
		AuthenticationHandler serverHandler = new AuthenticationHandler(new BlockingHandler(new HTTPServerHandler()));
		adminRouter.add("POST", ServerEndpoints.SERVER_RESTART, serverHandler);
		adminRouter.add("POST", ServerEndpoints.SERVER_STOP, serverHandler);
		
		// Build the servers
		webRouterHandler = Handlers.gracefulShutdown(Handlers.exceptionHandler(webRouter));
		adminRouterHandler = Handlers.gracefulShutdown(Handlers.exceptionHandler(adminRouter));
		
		logger.info("Starting server instances");
		if (serverHostUri.getScheme().equalsIgnoreCase("https")) {
			SSLContext ctx = new SSLContextFactory().createSslContext();
			webServer = Undertow.builder()
					.addHttpsListener(serverHostUri.getPort(), serverHostUri.getHost(), ctx)
					.setServerOption(UndertowOptions.ALWAYS_SET_DATE, true)
					.setHandler(webRouterHandler)
					.build();
			webServer.start();
			logger.info("... Web server has started at port " + serverHostUri.getPort());
			
			adminServer = Undertow.builder()
					.addHttpsListener(serverAdminPort, serverHostUri.getHost(), ctx)
					.setServerOption(UndertowOptions.ALWAYS_SET_DATE, true)
					.setHandler(adminRouterHandler)
					.build();
			adminServer.start();
			logger.info("... Admin server has started at port " + serverAdminPort);
		}
		else {
			webServer = Undertow.builder()
					.addHttpListener(serverHostUri.getPort(), serverHostUri.getHost())
					.setServerOption(UndertowOptions.ALWAYS_SET_DATE, true)
					.setHandler(webRouterHandler)
					.build();
			webServer.start();
			logger.info("... Web server has started at port " + serverHostUri.getPort());
			
			adminServer = Undertow.builder()
					.addHttpListener(serverAdminPort, serverHostUri.getHost())
					.setServerOption(UndertowOptions.ALWAYS_SET_DATE, true)
					.setHandler(adminRouterHandler)
					.build();
			adminServer.start();
			logger.info("... Admin server has started at port " + serverAdminPort);
		}
		isRunning = true;
	}

	private BlockingHandler loadAndCreateLogin(ServerConfiguration config) {
		String authClassName = config.getProperty(ServerProperties.AUTHENTICATION_CLASS);
		LoginService service = null;
		if (authClassName != null) {
			try {
				service = (LoginService) Class.forName(authClassName).newInstance();
				service.setConfig(config);
				
			} catch (ClassNotFoundException | InstantiationException | IllegalAccessException e) {
				// TODO Auto-generated catch block
				e.printStackTrace();
			}
		}
		return new BlockingHandler(new HTTPLoginService(service));
	}

	private TokenTable createLoginTokenTable() {
		long loginTimeout = TokenTable.DEFAULT_TIMEOUT_PERIOD;
		String loginTimeoutValue = serverConfiguration.getProperty(ServerProperties.LOGIN_TIMEOUT_PERIOD);
		if (loginTimeoutValue != null && !loginTimeoutValue.isEmpty()) {
			loginTimeout = Long.parseLong(loginTimeoutValue);
		}
		return new TokenTable(loginTimeout);
	}

	public void stop() throws ServerException {
		if (isRunning) {
			logger.info("Stopping server instances");
			try {
				if (webServer != null) {
					if (webRouterHandler != null) {
						webRouterHandler.shutdown();
					}
					webServer.stop();
					webServer = null;
					logger.info("... Web server has stopped");
				}
				if (adminServer != null) {
					if (adminRouterHandler != null) {
						adminRouterHandler.shutdown();
					}
					adminServer.stop();
					adminServer = null;
					logger.info("... Admin server has stopped");
				}
				isRunning = false;
			}
			catch (Exception e) {
				throw new ServerException(StatusCodes.INTERNAL_SERVER_ERROR, e.getMessage());
			}
		}
	}

	public void restart() throws ServerException {
		try {
			logger.info("Received request to restart");
			stop();
			reloadConfig();
			start();
		}
		catch (Exception e) {
			throw new ServerException(StatusCodes.INTERNAL_SERVER_ERROR, e.getMessage());
		}
	}

	public static void main(final String[] args) throws ServerException {
		try {
			HTTPServer s = new HTTPServer();
			s.start();
		}
		catch (Exception e) {
			throw new ServerException(StatusCodes.INTERNAL_SERVER_ERROR, e.getMessage());
		}
	}
}
