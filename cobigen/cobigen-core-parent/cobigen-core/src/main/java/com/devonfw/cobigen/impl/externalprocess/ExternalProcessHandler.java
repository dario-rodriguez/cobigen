package com.devonfw.cobigen.impl.externalprocess;

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.URL;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Paths;

import javax.xml.bind.UnmarshalException;

import org.codehaus.jackson.map.ObjectMapper;
import org.codehaus.jackson.map.ObjectWriter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.devonfw.cobigen.api.constants.ExternalProcessConstants;
import com.devonfw.cobigen.api.exception.CobiGenRuntimeException;
import com.devonfw.cobigen.impl.exceptions.ConnectionExceptionHandler;
import com.devonfw.cobigen.impl.exceptions.ConnectionExceptionHandler.ConnectionException;
import com.devonfw.cobigen.impl.exceptions.HttpConnectionException;
import com.devonfw.cobigen.impl.util.ExceptionUtil;
import com.devonfw.cobigen.impl.util.ProcessOutputUtil;

/**
 * Class for handling the external process creation and the communication with it.
 *
 */
public class ExternalProcessHandler {

    /** Logger instance. */
    private static final Logger LOG = LoggerFactory.getLogger(ExternalProcessHandler.class);

    /**
     * Port used for connecting to the server
     */
    private Integer port;

    /**
     * Host name of the server, by default is localhost
     */
    private String hostName;

    /**
     * HTTP connection instance for sending requests
     */
    private HttpURLConnection conn;

    /**
     * Native process instance
     */
    private Process process;

    /**
     * Used for handling all the errors our external process throws
     */
    private ProcessOutputUtil errorHandler = null;

    /**
     * URL for connecting with the different services of our external process
     */
    private URL url;

    /**
     * Path of the executable file (the server)
     */
    private String exePath = "";

    /**
     * Singleton instance of {@link ExternalProcessHandler}.
     */
    public static ExternalProcessHandler externalProcessHandler = null;

    /**
     * Exception handler related to connectivity to the server
     */
    private ConnectionExceptionHandler connectionExc = new ConnectionExceptionHandler();

    /**
     * Using singleton pattern, we will only have one instance of {@link ExternalProcessHandler}.
     *
     * @param hostName
     *            name of the server, normally localhost
     * @param port
     *            port to be used for connecting to the server
     * @return external process instance
     */
    public static ExternalProcessHandler getExternalProcessHandler(String hostName, Integer port) {

        if (externalProcessHandler == null) {
            externalProcessHandler = new ExternalProcessHandler(hostName, port);
        }

        return externalProcessHandler;
    }

    /**
     * Constructor of {@link ExternalProcessHandler}.
     *
     * @param hostName
     *            name of the server, normally localhost
     * @param port
     *            port to be used for connecting to the server
     */
    protected ExternalProcessHandler(String hostName, int port) {

        this.hostName = hostName;
        this.port = port;
    }

    /**
     * Tries to execute, as a new process, the executable file specified on the parameter. Also initializes
     * the error handler.
     *
     * @param path
     *            of the executable file to execute
     * @return true if it was able to execute the exe successfully
     */
    public boolean executingExe(String path, Class<?> activatorClass) {

        exePath = path;

        boolean execution = false;
        try {
            LOG.info("Loading server: " + exePath);

            String filePath = getExePath(activatorClass);

            if (new File(filePath).isFile()) {
                process = new ProcessBuilder(filePath, String.valueOf(port)).start();
            } else {
                filePath = activatorClass.getProtectionDomain().getCodeSource().getLocation().getPath();
                File jarFile = new File(filePath);
                String parentDirectory = jarFile.getParent();

                // Is the exe file already available on our directory?
                if (new File(parentDirectory + exePath).isFile()) {
                    process = new ProcessBuilder(parentDirectory + exePath, String.valueOf(port)).start();
                } else {
                    // We need to extract the file to our current directory
                    // Do we have write access?
                    if (Files.isWritable(Paths.get(jarFile.getParent()))) {
                        Files.copy(getClass().getResourceAsStream(exePath), Paths.get(parentDirectory + exePath));
                        process = new ProcessBuilder(parentDirectory + exePath, String.valueOf(port)).start();
                    } else {
                        // We are not able to extract the server
                        return false;
                    }
                }
            }

            // We try to get the error output
            errorHandler = new ProcessOutputUtil(process.getErrorStream(), "UTF-8");

            int retry = 0;
            while (!process.isAlive() && retry <= 10) {
                if (processHasErrors()) {
                    terminateProcessConnection();
                    return false;
                }
                retry++;
                LOG.info("Waiting process to be alive");
            }
            if (retry > 10) {
                return false;
            }
            execution = true;
        } catch (IOException e) {
            Throwable parseCause = ExceptionUtil.getCause(e, Exception.class, UnmarshalException.class);
            LOG.info(parseCause.toString(), e);
            LOG.info("Error starting the external process server", e);
            execution = false;
            throw new CobiGenRuntimeException("Error starting the external process: " + parseCause.toString());
        }
        return execution;

    }

    /**
     * @param activatorClass
     *
     */
    private String getExePath(Class<?> activatorClass) {
        String filePath = "";
        // We need to load the class that loaded this class because we are getting called from a plug-in
        // for instance, ts-plugin is calling this class, and the exe is on the classpath of ts-plugin
        if (getClass().getResource(exePath) == null) {

            if (getClass().getClassLoader().getResource(exePath) == null) {
                filePath = activatorClass.getProtectionDomain().getCodeSource().getLocation().getPath();
            } else {
                filePath = getClass().getClassLoader().getResource(exePath).getPath();
                return filePath;
            }
        } else {
            filePath = getClass().getResource(exePath).getPath();
            return filePath;
        }

        return filePath;
    }

    /**
     * Tries several times to start a new connection to the server. If the port is already in use, tries to
     * connect again with another port
     *
     * @return true if the ExternalProcess was able to connect to the server
     */
    public boolean initializeConnection() {

        for (int retry = 0; retry < 10; retry++) {
            try {
                startConnection();

                // Just check correct port acquisition
                if (acquirePort()) {
                    return true;
                }
            } catch (Exception e) {
                LOG.error("Connection to server failed, attempt number " + retry + ".");
                if (connectionExc.handle(e).equals(ConnectionException.MALFORMED_URL)) {
                    LOG.error("MalformedURLException: Connection to server failed, MalformedURL.", e);
                    return false;
                }

            } finally {
                conn.disconnect();
            }
        }
        return false;
    }

    /**
     * Tries to start a new connection to the server
     *
     * @throws MalformedURLException
     *             thrown when the URL is malformed
     * @throws IOException
     *             throws {@link IOException}
     */
    public void startConnection() throws IOException {

        url = new URL("http://" + hostName + ":" + port + "/processmanagement/");
        conn = (HttpURLConnection) url.openConnection();
        conn.connect();
    }

    /**
     * Tries to acquire a port. If the port is already in use, tries to execute again the external process
     * with another port
     *
     * @return true if a port has been acquired
     */
    private boolean acquirePort() {

        // If there is any error, probably it is because the port is blocked
        if (isNotConnected() || processHasErrors()) {
            restartServer();
        } else {
            return true;
        }
        return false;
    }

    /**
     * Checks whether the external process error handler has any content. If so, it means there is an error
     *
     * @return true if external process contains errors
     */
    private boolean processHasErrors() {

        if (errorHandler != null) {
            // External process has not printed any error
            if (errorHandler.getText().isEmpty()) {
                return false;
            }
        }

        return true;
    }

    /**
     * Sends a dummy request to the server in order to check if it is not connected
     *
     * @return true if it is not connected
     */
    public boolean isNotConnected() {

        try {
            getConnection("HEAD", "Content-Type", "text/plain", "");
            int responseCode;
            responseCode = conn.getResponseCode();
            if (responseCode < 500) {
                return false;
            }
        } catch (IOException e) {
            LOG.error("Connection to server failed, port blocked. Trying other port...", e);
        }

        return true;
    }

    /**
     * Gets an HTTP request using the specified parameters
     *
     * @param httpMethod
     *            HTTP method to use (POST, GET, PUT...)
     * @param headerProperty
     *            Header property to use (content-type, content-lenght
     * @param mediaType
     *            type of media (application/json, text/plain...)
     * @param endpointURL
     *            The endpoint URL of the service
     * @return the {@link HttpURLConnection} to the endpoint
     */
    public HttpURLConnection getConnection(String httpMethod, String headerProperty, String mediaType,
        String endpointURL) {

        try {
            URL currentURL = new URL(url.getProtocol(), url.getHost(), url.getPort(), url.getFile() + endpointURL);

            conn = (HttpURLConnection) currentURL.openConnection();

            conn.setDoOutput(true);
            conn.setRequestMethod(httpMethod);
            conn.setRequestProperty(headerProperty, mediaType);
            conn.setConnectTimeout(ExternalProcessConstants.CONNECTION_TIMEOUT);
            conn.setReadTimeout(ExternalProcessConstants.CONNECTION_TIMEOUT);

        } catch (Exception e) {
            Throwable parseCause = ExceptionUtil.getCause(e, Exception.class, UnmarshalException.class);
            LOG.info(parseCause.toString(), e);
            ConnectionExceptionHandler connectionExc = new ConnectionExceptionHandler();
            connectionExc.handle(e);
        }
        return conn;
    }

    /**
     * Sends a transfer object to the server, by using http connection
     * @param dataTo
     *            Data to transfer, it should be a serializable class
     * @param conn
     *            {@link HttpURLConnection} to the server, containing also the URL
     * @param charsetName
     *            the name of a supported {@link Charset}
     * @return returns true when the status code of the request was 200 or 201
     */
    public boolean sendRequest(Object dataTo, HttpURLConnection conn, String charsetName) {
        ObjectWriter objWriter;
        objWriter = new ObjectMapper().writer().withDefaultPrettyPrinter();
        String jsonMergerTo;
        try (OutputStream os = conn.getOutputStream();
            OutputStreamWriter osw = new OutputStreamWriter(os, charsetName);) {

            jsonMergerTo = objWriter.writeValueAsString(dataTo);
            // We need to escape new lines because otherwise our JSON gets corrupted
            jsonMergerTo = jsonMergerTo.replace("\\n", "\\\\n");

            osw.write(jsonMergerTo);
            osw.flush();

            for (int i = 0; i < ExternalProcessConstants.NUMBER_OF_RETRIES; i++) {
                conn.connect();
                int statusCode = conn.getResponseCode();

                if (statusCode == HttpURLConnection.HTTP_OK || statusCode == HttpURLConnection.HTTP_CREATED) {
                    // Successful request
                    return true;
                }
                if (shouldNotRetry(conn.getResponseCode())) {
                    return false;
                }
            }
            return false;
        } catch (IOException e) {
            // TODO Auto-generated catch block
            e.printStackTrace();
        }

        return false;

    }

    /**
     * Checks whether the HTTP request should NOT be retried
     * @param statusCode
     *            code of the HTTP request
     * @return true when we should not retry because the status code describes a unavoidable case
     * @throws IOException
     *             throws {@link IOException}
     */
    private boolean shouldNotRetry(int statusCode) throws IOException {

        switch (statusCode) {

        case HttpURLConnection.HTTP_MOVED_TEMP:
            return false;

        case HttpURLConnection.HTTP_UNAVAILABLE:
            return false;

        default:
            try {
                throw new HttpConnectionException("Failed : HTTP error code : " + conn.getResponseCode());
            } catch (HttpConnectionException e) {
                return true;
            }
        }

    }

    /**
     * Terminates the current connection to the server, the process and the error handler. Then tries to load
     * a new server on a different port number
     */
    public void restartServer() {
        terminateProcessConnection();
        port++;
        executingExe(exePath, this.getClass());
    }

    /**
     * Terminates the connection to the server (if was still connected), the process (if it was still alive)
     * and the error output handler (if it was still alive)
     * @return true if it was able to terminate the process
     */
    public Boolean terminateProcessConnection() {

        if (conn != null) {
            conn.disconnect();
        }
        if (process == null) {
            return true;
        }
        // if (!terminateProcess(process, "nestserver.exe")) {
        // return false;
        // }
        if (process.isAlive()) {
            process.destroyForcibly();
            LOG.info("Closing process instance");
        }
        // Let's kill the process error handler
        if (errorHandler.isAlive()) {
            try {
                errorHandler.join();
            } catch (InterruptedException e) {
                LOG.info("Error while trying to close the process error handler", e);
                e.printStackTrace();
                return false;
            }
        }
        return true;
    }

}
