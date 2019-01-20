import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

// openie stuff
import scala.collection.Iterator;
import scala.collection.Seq;
import edu.knowitall.openie.*;
import edu.knowitall.tool.parse.ClearParser;
import edu.knowitall.tool.postag.ClearPostagger;
import edu.knowitall.tool.postag.Postagger;
import edu.knowitall.tool.srl.ClearSrl;
import edu.knowitall.tool.tokenize.ClearTokenizer;

public class OpenIEServer {
    private static final String HOSTNAME = "localhost";
    private static int PORT = 8080; // default; allow custom setting as well
    private static final int BACKLOG = 1;

    private static final Logger LOGGER = Logger.getLogger(OpenIEServer.class.getName());

    private static final String HEADER_ALLOW = "Allow";
    private static final String HEADER_CONTENT_TYPE = "Content-Type";

    private static final Charset CHARSET = StandardCharsets.UTF_8;

    private static final int STATUS_OK = 200;
    private static final int STATUS_METHOD_NOT_ALLOWED = 405;

    private static final int NO_RESPONSE_LENGTH = -1;

    private static final String METHOD_GET = "GET";
    private static final String METHOD_OPTIONS = "OPTIONS";
    private static final String ALLOWED_METHODS = METHOD_GET + "," + METHOD_OPTIONS;

    // name of the GET request parameter used as text fed to openie
    private static final String TEXT_PARAM = "text";

    public static void main(final String... args) throws IOException {
        if (args.length < 1) {
            LOGGER.info("Serving Openie 5.0 on default port: " + PORT);
        }
        else {
            PORT = Integer.parseInt(args[0]);
            LOGGER.info("Serving Openie 5.0 on port: " + PORT);
        }

        final HttpServer server = HttpServer.create(new InetSocketAddress(HOSTNAME, PORT), BACKLOG);

        OpenIE openIE = new OpenIE(new ClearParser(new ClearPostagger(new ClearTokenizer())),
                new ClearSrl(), false, false);
        LOGGER.info("Finished loading Openie...");

        server.createContext("/", he -> {
            try {
                final Headers headers = he.getResponseHeaders();
                final String requestMethod = he.getRequestMethod().toUpperCase();
                switch (requestMethod) {
                    case METHOD_GET:
                        final Map<String, List<String>> requestParameters = getRequestParameters(he.getRequestURI());
                        List<String> texts = requestParameters.get(TEXT_PARAM);
                        // we only allow one 'text' value for now
                        if (texts != null) {
                            String text = texts.get(0);
                            // extract relations here
                            final String responseBody = openieExtractRelations(openIE, text);
                            headers.set(HEADER_CONTENT_TYPE, String.format("application/json; charset=%s", CHARSET));
                            final byte[] rawResponseBody = responseBody.getBytes(CHARSET);
                            he.sendResponseHeaders(STATUS_OK, rawResponseBody.length);
                            he.getResponseBody().write(rawResponseBody);
                        }
                        else {
                            LOGGER.info("Null request");
                        }
                        break;
                    case METHOD_OPTIONS:
                        headers.set(HEADER_ALLOW, ALLOWED_METHODS);
                        he.sendResponseHeaders(STATUS_OK, NO_RESPONSE_LENGTH);
                        break;
                    default:
                        headers.set(HEADER_ALLOW, ALLOWED_METHODS);
                        he.sendResponseHeaders(STATUS_METHOD_NOT_ALLOWED, NO_RESPONSE_LENGTH);
                        break;
                }
            } finally {
                he.close();
            }
        });
        server.start();
    }

    private static Map<String, List<String>> getRequestParameters(final URI requestUri) {
        final Map<String, List<String>> requestParameters = new LinkedHashMap<>();
        final String requestQuery = requestUri.getRawQuery();
        if (requestQuery != null) {
            final String[] rawRequestParameters = requestQuery.split("[&;]", -1);
            for (final String rawRequestParameter : rawRequestParameters) {
                final String[] requestParameter = rawRequestParameter.split("=", 2);
                final String requestParameterName = decodeUrlComponent(requestParameter[0]);
                requestParameters.putIfAbsent(requestParameterName, new ArrayList<>());
                final String requestParameterValue = requestParameter.length > 1 ? decodeUrlComponent(requestParameter[1]) : null;
                requestParameters.get(requestParameterName).add(requestParameterValue);
            }
        }
        return requestParameters;
    }

    private static String decodeUrlComponent(final String urlComponent) {
        try {
            return URLDecoder.decode(urlComponent, CHARSET.name());
        } catch (final UnsupportedEncodingException ex) {
            throw new InternalError(ex);
        }
    }

    private static String openieExtractRelations(OpenIE openie, String text) {
        // example JSON returned:
        // [{"confidence":0.8,"arg1":"Obama","rel":"gave","arg2s":["a speech","a speech on Tuesday"]}]
        // will be a list of instances, each a dictionary
        LOGGER.info("Extracting from text: \n" + text);
        StringBuilder sb = new StringBuilder();
        sb.append("["); // begin instances list

        Seq<Instance> extractions = openie.extract(text);

        LOGGER.info(extractions);

        Iterator<Instance> iterator = extractions.iterator();
        while (iterator.hasNext()) {
            Instance inst = iterator.next();
            sb.append("{"); // begin instance dict

            sb.append("\"confidence\":");
            sb.append(inst.confidence());
            sb.append(",");

            sb.append("\"context\":");
            sb.append("\"");
            sb.append(inst.extr().context().toString());
            sb.append("\"");
            sb.append(",");

            sb.append("\"negated\":");
            sb.append((inst.extr().negated()) ? "1" : "0");
            sb.append(",");

            sb.append("\"passive\":");
            sb.append((inst.extr().passive()) ? "1" : "0");
            sb.append(",");

            sb.append("\"string\":");
            sb.append("\"");
            sb.append(inst.extr().toString());  // should be equivant to extr().tripleString()
            sb.append("\"");
            sb.append(",");

            sb.append("\"rel\":");
            sb.append("\"");
            sb.append(inst.extr().rel().text());
            sb.append("\"");
            sb.append(",");

            sb.append("\"arg1\":");
            sb.append("\"");
            sb.append(inst.extr().arg1().text());
            sb.append("\"");
            sb.append(",");

            sb.append("\"arg2s\":"); // begin list of arg2s
            sb.append("[");
            Iterator<Argument> argIter = inst.extr().arg2s().iterator();
            while (argIter.hasNext()) {
                sb.append("\"");
                Argument arg = argIter.next();
                sb.append(arg.text());
                sb.append("\"");
                sb.append(",");
            }
            // pop the trailing comma for valid JSON
            if (sb.charAt(sb.length() - 1) == ',')
                sb.setLength(sb.length() - 1);
            sb.append("]"); // end args2 list

            sb.append("}"); // end instance dict

            sb.append(",");
        }
        // pop the trailing comma for valid JSON
        if (sb.charAt(sb.length() - 1) == ',')
            sb.setLength(sb.length() - 1);

        sb.append("]"); // end instances list

        return sb.toString();
    }
}
