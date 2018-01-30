package me.snowdrop.protocol;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import io.undertow.UndertowOptions;
import io.undertow.client.ClientCallback;
import io.undertow.client.ClientConnection;
import io.undertow.client.ClientExchange;
import io.undertow.client.ClientRequest;
import io.undertow.client.ClientResponse;
import io.undertow.client.UndertowClient;
import io.undertow.connector.ByteBufferPool;
import io.undertow.server.DefaultByteBufferPool;
import io.undertow.util.AttachmentKey;
import io.undertow.util.Headers;
import io.undertow.util.HttpString;
import io.undertow.util.Methods;
import io.undertow.util.StringReadChannelListener;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.PropertySource;
import org.springframework.test.context.junit4.SpringRunner;
import org.xnio.ChannelListeners;
import org.xnio.IoUtils;
import org.xnio.OptionMap;
import org.xnio.Options;
import org.xnio.Xnio;
import org.xnio.XnioWorker;
import org.xnio.channels.StreamSinkChannel;

@RunWith(SpringRunner.class)
@SpringBootTest(classes = Http2Application.class, webEnvironment = SpringBootTest.WebEnvironment.DEFINED_PORT)
@PropertySource("classpath:application.properties")
public class Http2WithUndertowClientTest {

    private final static String KEYSTORE_LOCATION = "src/main/resources/keystore.jks";
    private final static String KEYSTORE_PASS = "secret";

    private static final AttachmentKey<String> RESPONSE_BODY = AttachmentKey.create(String.class);
    private static final int BUFFER_SIZE = Integer.getInteger("test.bufferSize", 1024 * 16 - 20);
    private static final ByteBufferPool POOL = new DefaultByteBufferPool(true, BUFFER_SIZE, 1000, 10, 100);

    private static final OptionMap DEFAULT_OPTIONS;
    private static final URI ADDRESS;

    private XnioWorker worker;

    static {
        final OptionMap.Builder builder = OptionMap.builder()
            .set(Options.WORKER_IO_THREADS, 8)
            .set(Options.TCP_NODELAY, true)
            .set(Options.KEEP_ALIVE, true)
            .set(UndertowOptions.ENABLE_HTTP2, true)
            .set(Options.WORKER_NAME, "Client");

        DEFAULT_OPTIONS = builder.getMap();
        try {
            ADDRESS = new URI("http://localhost:8080");
        } catch (URISyntaxException e) {
            throw new RuntimeException(e);
        }
    }

    @Before
    public void setUp() throws Exception {
        final Xnio xnio = Xnio.getInstance();
        worker = xnio.createWorker(null, DEFAULT_OPTIONS);
    }

    @After
    public void tearDown() throws Exception {
        worker.shutdown();
    }

    @Test
    public void testHttp2WithHttp2ClientAndHttp() throws Exception {
        final int N = 1;

        UndertowClient client = UndertowClient.getInstance();
        ClientConnection connection = client.connect(ADDRESS, worker, POOL, DEFAULT_OPTIONS).get();

        final List<ClientResponse> responses = new CopyOnWriteArrayList<>();
        final CountDownLatch latch = new CountDownLatch(N);

        try {
            connection.getIoThread().execute(() -> {
                for (int i = 0; i < N; i++) {
                    final ClientRequest request = new ClientRequest().setMethod(Methods.GET).setPath("/"); //.setProtocol(HttpString.tryFromString("HTTP/2.0"));
                    request.getRequestHeaders().put(Headers.HOST, "localhost");
                    connection.sendRequest(request, createClientCallback(responses, latch));
                }
            });

            latch.await(10, TimeUnit.SECONDS);

            Assert.assertEquals("Invalid responses size.", N, responses.size());
            for (final ClientResponse response : responses) {
                System.out.println("response.protocol = " + response.getProtocol());
                Assert.assertEquals("hello!", response.getAttachment(RESPONSE_BODY));
            }
        } finally {
            IoUtils.safeClose(connection);
        }
    }

    //@Test
    public void testHttp2WithHttpClientAndHttp() throws Exception {
    }

    //@Test
    public void testHttp2WithHttpClientAndSsl() throws Exception {
    }

    private ClientCallback<ClientExchange> createClientCallback(final List<ClientResponse> responses, final CountDownLatch latch) {
        return new ClientCallback<ClientExchange>() {
            @Override
            public void completed(ClientExchange result) {
                result.setResponseListener(new ClientCallback<ClientExchange>() {
                    @Override
                    public void completed(final ClientExchange result) {
                        responses.add(result.getResponse());
                        new StringReadChannelListener(result.getConnection().getBufferPool()) {

                            @Override
                            protected void stringDone(String string) {
                                result.getResponse().putAttachment(RESPONSE_BODY, string);
                                latch.countDown();
                            }

                            @Override
                            protected void error(IOException e) {
                                e.printStackTrace();

                                latch.countDown();
                            }
                        }.setup(result.getResponseChannel());
                    }

                    @Override
                    public void failed(IOException e) {
                        e.printStackTrace();

                        latch.countDown();
                    }
                });
                try {
                    result.getRequestChannel().shutdownWrites();
                    if (!result.getRequestChannel().flush()) {
                        result.getRequestChannel().getWriteSetter().set(ChannelListeners.<StreamSinkChannel>flushingChannelListener(null, null));
                        result.getRequestChannel().resumeWrites();
                    }
                } catch (IOException e) {
                    e.printStackTrace();
                    latch.countDown();
                }
            }

            @Override
            public void failed(IOException e) {
                e.printStackTrace();
                latch.countDown();
            }
        };
    }

}