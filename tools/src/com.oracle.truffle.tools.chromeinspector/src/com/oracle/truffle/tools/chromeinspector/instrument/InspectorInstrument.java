/*
 * Copyright (c) 2017, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.  Oracle designates this
 * particular file as subject to the "Classpath" exception as provided
 * by Oracle in the LICENSE file that accompanied this code.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
 * or visit www.oracle.com if you need additional information or have any
 * questions.
 */
package com.oracle.truffle.tools.chromeinspector.instrument;

import java.io.File;
import java.io.IOException;
import java.io.PrintWriter;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import org.graalvm.options.OptionCategory;
import org.graalvm.options.OptionDescriptor;
import org.graalvm.options.OptionDescriptors;
import org.graalvm.options.OptionKey;
import org.graalvm.options.OptionType;
import org.graalvm.options.OptionValues;
import org.graalvm.polyglot.io.MessageEndpoint;
import org.graalvm.polyglot.io.MessageTransport;

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.TruffleContext;
import com.oracle.truffle.api.instrumentation.ContextsListener;
import com.oracle.truffle.api.instrumentation.EventBinding;
import com.oracle.truffle.api.instrumentation.TruffleInstrument;
import com.oracle.truffle.api.interop.TruffleObject;
import com.oracle.truffle.api.nodes.LanguageInfo;

import com.oracle.truffle.tools.chromeinspector.InspectorExecutionContext;
import com.oracle.truffle.tools.chromeinspector.objects.Inspector;
import com.oracle.truffle.tools.chromeinspector.client.InspectWSClient;
import com.oracle.truffle.tools.chromeinspector.server.ConnectionWatcher;
import com.oracle.truffle.tools.chromeinspector.server.InspectServerSession;
import com.oracle.truffle.tools.chromeinspector.server.WSInterceptorServer;
import com.oracle.truffle.tools.chromeinspector.server.WebSocketServer;
import com.oracle.truffle.tools.chromeinspector.server.InspectorServerConnection;

/**
 * Chrome inspector as an instrument.
 */
@TruffleInstrument.Registration(id = InspectorInstrument.INSTRUMENT_ID, name = "Chrome Inspector", version = InspectorInstrument.VERSION, services = TruffleObject.class)
public final class InspectorInstrument extends TruffleInstrument {

    private static final int DEFAULT_PORT = 9229;
    private static final HostAndPort DEFAULT_ADDRESS = new HostAndPort(null, DEFAULT_PORT);
    private Server server;
    private ConnectionWatcher connectionWatcher;

    static final OptionType<HostAndPort> ADDRESS_OR_BOOLEAN = new OptionType<>("[[host:]port]", DEFAULT_ADDRESS, (address) -> {
        if (address.isEmpty() || address.equals("true")) {
            return DEFAULT_ADDRESS;
        } else {
            int colon = address.indexOf(':');
            String port;
            String host;
            if (colon >= 0) {
                port = address.substring(colon + 1);
                host = address.substring(0, colon);
            } else {
                port = address;
                host = null;
            }
            return new HostAndPort(host, port);
        }
    }, (address) -> address.verify());

    static final OptionType<List<URI>> SOURCE_PATH = new OptionType<>("folder" + File.pathSeparator + "file.zip" + File.pathSeparator + "...", Collections.emptyList(), (str) -> {
        if (str.isEmpty()) {
            return Collections.emptyList();
        }
        List<URI> uris = new ArrayList<>();
        int i1 = 0;
        while (i1 < str.length()) {
            int i2 = str.indexOf(File.pathSeparatorChar, i1);
            if (i2 < 0) {
                i2 = str.length();
            }
            String path = str.substring(i1, i2);
            try {
                uris.add(createURIFromPath(path));
            } catch (URISyntaxException ex) {
                throw new IllegalArgumentException("Wrong path: " + path, ex);
            }
            i1 = i2 + 1;
        }
        return uris;
    });

    private static URI createURIFromPath(String path) throws URISyntaxException {
        String lpath = path.toLowerCase();
        int index = 0;
        File jarFile = null;
        while (index < lpath.length()) {
            int zi = lpath.indexOf(".zip", index);
            int ji = lpath.indexOf(".jar", index);
            if (zi >= 0 && zi < ji || ji < 0) {
                ji = zi;
            }
            if (ji >= 0) {
                index = ji + 4;
                File jar = new File(path.substring(0, index));
                if (jar.isFile()) {
                    jarFile = jar;
                    break;
                }
            } else {
                index = path.length();
            }
        }
        if (jarFile != null) {
            StringBuilder ssp = new StringBuilder("file://").append(jarFile.getAbsolutePath());
            if (index < path.length()) {
                if (path.charAt(index) != '!') {
                    ssp.append('!');
                }
                ssp.append(path.substring(index));
            } else {
                ssp.append("!/");
            }
            return new URI("jar", ssp.toString(), null);
        } else {
            return new File(path).toPath().toUri();
        }
    }

    @com.oracle.truffle.api.Option(name = "", help = "Start the Chrome inspector on [[host:]port]. (default: <loopback address>:" + DEFAULT_PORT + ")", category = OptionCategory.USER) //
    static final OptionKey<HostAndPort> Inspect = new OptionKey<>(DEFAULT_ADDRESS, ADDRESS_OR_BOOLEAN);

    @com.oracle.truffle.api.Option(help = "Attach to an existing endpoint instead of creating a new one. (default:false)", category = OptionCategory.DEBUG) //
    static final OptionKey<Boolean> Attach = new OptionKey<>(false);

    @com.oracle.truffle.api.Option(help = "Suspend the execution at first executed source line. (default:true)", category = OptionCategory.USER) //
    static final OptionKey<Boolean> Suspend = new OptionKey<>(true);

    @com.oracle.truffle.api.Option(help = "Do not execute any source code until inspector client is attached. (default:false)", category = OptionCategory.EXPERT) //
    static final OptionKey<Boolean> WaitAttached = new OptionKey<>(false);

    @com.oracle.truffle.api.Option(help = "Specifies list of directories or ZIP/JAR files representing source path. (default:none)", category = OptionCategory.EXPERT) //
    static final OptionKey<List<URI>> SourcePath = new OptionKey<>(Collections.emptyList(), SOURCE_PATH);

    @com.oracle.truffle.api.Option(help = "Hide internal errors that can occur as a result of debugger inspection. (default:false)", category = OptionCategory.EXPERT) //
    static final OptionKey<Boolean> HideErrors = new OptionKey<>(false);

    @com.oracle.truffle.api.Option(help = "Path to the chrome inspect. (default: randomly generated)", category = OptionCategory.EXPERT) //
    static final OptionKey<String> Path = new OptionKey<>("");

    @com.oracle.truffle.api.Option(help = "Inspect internal sources. (default:false)", category = OptionCategory.DEBUG) //
    static final OptionKey<Boolean> Internal = new OptionKey<>(false);

    @com.oracle.truffle.api.Option(help = "Inspect language initialization. (default:false)", category = OptionCategory.DEBUG) //
    static final OptionKey<Boolean> Initialization = new OptionKey<>(false);

    @com.oracle.truffle.api.Option(help = "Use TLS/SSL. (default:false)", category = OptionCategory.EXPERT) //
    static final OptionKey<Boolean> Secure = new OptionKey<>(false);

    @com.oracle.truffle.api.Option(help = "File path to keystore used for secure connection. (default:javax.net.ssl.keyStore system property)", category = OptionCategory.EXPERT) //
    static final OptionKey<String> KeyStore = new OptionKey<>("");

    @com.oracle.truffle.api.Option(help = "The keystore type. (default:javax.net.ssl.keyStoreType system property, or \\\"JKS\\\")", category = OptionCategory.EXPERT) //
    static final OptionKey<String> KeyStoreType = new OptionKey<>("");

    @com.oracle.truffle.api.Option(help = "The keystore password. (default:javax.net.ssl.keyStorePassword system property)", category = OptionCategory.EXPERT) //
    static final OptionKey<String> KeyStorePassword = new OptionKey<>("");

    @com.oracle.truffle.api.Option(help = "Password for recovering keys from a keystore. (default:javax.net.ssl.keyPassword system property, or keystore password)", category = OptionCategory.EXPERT) //
    static final OptionKey<String> KeyPassword = new OptionKey<>("");

    public static final String INSTRUMENT_ID = "inspect";
    static final String VERSION = "0.1";

    @Override
    protected void onCreate(Env env) {
        OptionValues options = env.getOptions();
        if (options.hasSetOptions()) {
            HostAndPort hostAndPort = options.get(Inspect);
            connectionWatcher = new ConnectionWatcher();
            try {
                InetSocketAddress socketAddress = hostAndPort.createSocket();
                server = new Server(env, "Main Context", socketAddress, options.get(Attach), options.get(Suspend), options.get(WaitAttached), options.get(HideErrors), options.get(Internal),
                                options.get(Initialization), options.get(Path), options.get(Secure), new KeyStoreOptions(options), options.get(SourcePath), connectionWatcher);
            } catch (IOException e) {
                throw new InspectorIOException(hostAndPort.getHostPort(), e);
            }
        }

        env.registerService(new Inspector(server != null ? server.getConnection() : null, new InspectorServerConnection.Open() {
            @Override
            @SuppressWarnings("all") // The parameters port and host should not be assigned
            public synchronized InspectorServerConnection open(int port, String host, boolean wait) {
                if (server != null) {
                    return null;
                }
                HostAndPort hostAndPort = options.get(Inspect);
                if (port < 0) {
                    port = hostAndPort.port;
                }
                if (host == null) {
                    host = hostAndPort.host;
                }
                connectionWatcher = new ConnectionWatcher();
                hostAndPort = new HostAndPort(host, port);
                try {
                    InetSocketAddress socketAddress = hostAndPort.createSocket();
                    server = new Server(env, "Main Context", socketAddress, false, false, wait, options.get(HideErrors), options.get(Internal),
                                    options.get(Initialization), null, options.get(Secure), new KeyStoreOptions(options), options.get(SourcePath), connectionWatcher);
                } catch (IOException e) {
                    PrintWriter info = new PrintWriter(env.err());
                    info.println(new InspectorIOException(hostAndPort.getHostPort(), e).getLocalizedMessage());
                    info.flush();
                }
                return server != null ? server.getConnection() : null;
            }
        }, new Supplier<InspectorExecutionContext>() {
            @Override
            public InspectorExecutionContext get() {
                if (server != null) {
                    return server.getConnection().getExecutionContext();
                } else {
                    return new InspectorExecutionContext("Main Context", options.get(Internal), options.get(Initialization), env, Collections.emptyList(), new PrintWriter(env.err()));
                }
            }
        }));
    }

    @Override
    protected void onFinalize(Env env) {
        if (connectionWatcher != null && connectionWatcher.shouldWaitForClose()) {
            PrintWriter info = new PrintWriter(env.out());
            info.println("Waiting for the debugger to disconnect...");
            info.flush();
            connectionWatcher.waitForClose();
        }
        if (server != null) {
            try {
                server.close();
            } catch (IOException ioex) {
            }
        }
    }

    @Override
    protected OptionDescriptors getOptionDescriptors() {
        // Provide dynamic help example
        OptionDescriptors descriptors = new InspectorInstrumentOptionDescriptors();
        return new OptionDescriptors() {
            @Override
            public OptionDescriptor get(String optionName) {
                return descriptors.get(optionName);
            }

            @Override
            public Iterator<OptionDescriptor> iterator() {
                Iterator<OptionDescriptor> iterator = descriptors.iterator();
                return new Iterator<OptionDescriptor>() {
                    @Override
                    public boolean hasNext() {
                        return iterator.hasNext();
                    }

                    @Override
                    public OptionDescriptor next() {
                        OptionDescriptor descriptor = iterator.next();
                        if (descriptor.getKey() == SourcePath) {
                            String example = " Example: " + File.separator + "projects" + File.separator + "foo" + File.separator + "src" + File.pathSeparator + "sources.jar" + File.pathSeparator +
                                            "package.zip!/src";
                            descriptor = OptionDescriptor.newBuilder(SourcePath, descriptor.getName()).deprecated(descriptor.isDeprecated()).category(descriptor.getCategory()).help(
                                            descriptor.getHelp() + example).build();
                        }
                        return descriptor;
                    }
                };
            }
        };
    }

    private static final class HostAndPort {

        private final String host;
        private String portStr;
        private int port;
        private InetAddress inetAddress;

        HostAndPort(String host, int port) {
            this.host = host;
            this.port = port;
        }

        HostAndPort(String host, String portStr) {
            this.host = host;
            this.portStr = portStr;
        }

        void verify() {
            // Check port:
            if (port == 0) {
                try {
                    port = Integer.parseInt(portStr);
                } catch (NumberFormatException e) {
                    throw new IllegalArgumentException("Port is not a number: " + portStr);
                }
            }
            if (port <= 0 || port > 65535) {
                throw new IllegalArgumentException("Invalid port number: " + port);
            }
            // Check host:
            if (host != null && !host.isEmpty()) {
                try {
                    inetAddress = InetAddress.getByName(host);
                } catch (UnknownHostException ex) {
                    throw new IllegalArgumentException(ex.getLocalizedMessage(), ex);
                }
            }
        }

        String getHostPort() {
            String hostName = host;
            if (hostName == null || hostName.isEmpty()) {
                if (inetAddress != null) {
                    hostName = inetAddress.toString();
                } else {
                    hostName = InetAddress.getLoopbackAddress().toString();
                }
            }
            return hostName + ":" + port;
        }

        InetSocketAddress createSocket() {
            InetAddress ia;
            if (inetAddress == null) {
                ia = InetAddress.getLoopbackAddress();
            } else {
                ia = inetAddress;
            }
            return new InetSocketAddress(ia, port);
        }
    }

    private static final class Server {

        private InspectorWSConnection wss;
        private final String wsspath;
        private final String wsURL;
        private final InspectorExecutionContext executionContext;

        Server(final Env env, final String contextName, final InetSocketAddress socketAdress, final boolean attach, final boolean debugBreak, final boolean waitAttached, final boolean hideErrors,
                        final boolean inspectInternal, final boolean inspectInitialization, final String pathOrNull, final boolean secure, final KeyStoreOptions keyStoreOptions,
                        final List<URI> sourcePath, final ConnectionWatcher connectionWatcher) throws IOException {
            PrintWriter info = new PrintWriter(env.err());
            if (pathOrNull == null || pathOrNull.isEmpty()) {
                wsspath = "/" + Long.toHexString(System.identityHashCode(env)) + "-" + Long.toHexString(System.nanoTime() ^ System.identityHashCode(env));
            } else {
                String head = pathOrNull.startsWith("/") ? "" : "/";
                wsspath = head + pathOrNull;
            }

            PrintWriter err = (hideErrors) ? null : info;
            executionContext = new InspectorExecutionContext(contextName, inspectInternal, inspectInitialization, env, sourcePath, err);
            if (attach) {
                wss = new InspectWSClient(socketAdress, wsspath, executionContext, debugBreak, secure, keyStoreOptions, connectionWatcher, info);
                wsURL = ((InspectWSClient) wss).getURI().toString();
            } else {
                URI wsuri;
                try {
                    wsuri = new URI(secure ? "wss" : "ws", null, socketAdress.getAddress().getHostAddress(), socketAdress.getPort(), wsspath, null, null);
                } catch (URISyntaxException ex) {
                    throw new IOException(ex);
                }
                InspectServerSession iss = InspectServerSession.create(executionContext, debugBreak, connectionWatcher);
                WSInterceptorServer interceptor = new WSInterceptorServer(wsuri, iss, connectionWatcher);
                MessageEndpoint serverEndpoint;
                try {
                    serverEndpoint = env.startServer(wsuri, iss);
                } catch (MessageTransport.VetoException vex) {
                    throw new IOException(vex.getLocalizedMessage());
                }
                if (serverEndpoint == null) {
                    interceptor.close(wsspath);
                    wss = WebSocketServer.get(socketAdress, wsspath, executionContext, debugBreak, secure, keyStoreOptions, connectionWatcher, iss);
                    wsURL = buildAddress(socketAdress.getAddress().getHostAddress(), wss.getPort(), wsspath, secure);
                    String address = DEV_TOOLS_PREFIX + wsURL;
                    info.println("Debugger listening on port " + wss.getPort() + ".");
                    info.println("To start debugging, open the following URL in Chrome:");
                    info.println("    " + address);
                    info.flush();
                } else {
                    interceptor.opened(serverEndpoint);
                    wss = interceptor;
                    wsURL = wsuri.toString();
                }
            }
            if (debugBreak || waitAttached) {
                final AtomicReference<EventBinding<?>> execEnter = new AtomicReference<>();
                final AtomicBoolean disposeBinding = new AtomicBoolean(false);
                execEnter.set(env.getInstrumenter().attachContextsListener(new ContextsListener() {
                    @Override
                    public void onContextCreated(TruffleContext context) {
                    }

                    @Override
                    public void onLanguageContextCreated(TruffleContext context, LanguageInfo language) {
                        if (inspectInitialization) {
                            waitForRunPermission();
                        }
                    }

                    @Override
                    public void onLanguageContextInitialized(TruffleContext context, LanguageInfo language) {
                        if (!inspectInitialization) {
                            waitForRunPermission();
                        }
                    }

                    @Override
                    public void onLanguageContextFinalized(TruffleContext context, LanguageInfo language) {
                    }

                    @Override
                    public void onLanguageContextDisposed(TruffleContext context, LanguageInfo language) {
                    }

                    @Override
                    public void onContextClosed(TruffleContext context) {
                    }

                    @TruffleBoundary
                    private void waitForRunPermission() {
                        try {
                            executionContext.waitForRunPermission();
                        } catch (InterruptedException ex) {
                        }
                        final EventBinding<?> binding = execEnter.getAndSet(null);
                        if (binding != null) {
                            binding.dispose();
                        } else {
                            disposeBinding.set(true);
                        }
                    }
                }, true));
                if (disposeBinding.get()) {
                    execEnter.get().dispose();
                }
            }
        }

        private static final String DEV_TOOLS_PREFIX = "chrome-devtools://devtools/bundled/js_app.html?";
        private static final String WS_PREFIX = "ws=";
        private static final String WS_PREFIX_SECURE = "wss=";

        private static String buildAddress(String hostAddress, int port, String path, boolean secure) {
            String prefix = secure ? WS_PREFIX_SECURE : WS_PREFIX;
            return prefix + hostAddress + ":" + port + path;
        }

        public void close() throws IOException {
            if (wss != null) {
                wss.close(wsspath);
                wss = null;
            }
        }

        InspectorServerConnection getConnection() {
            return new InspectorServerConnection() {

                @Override
                public String getWSPath() {
                    return wsspath;
                }

                @Override
                public String getURL() {
                    return wsURL;
                }

                @Override
                public void close() throws IOException {
                    wss.close(getWSPath());
                }

                @Override
                public InspectorExecutionContext getExecutionContext() {
                    return executionContext;
                }

                @Override
                public void consoleAPICall(String type, Object text) {
                    wss.consoleAPICall(getWSPath(), type, text);
                }
            };
        }
    }
}
