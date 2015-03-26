package io.hawt.web.tomcat;

import io.hawt.util.Predicate;
import org.apache.commons.codec.digest.DigestUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.w3c.dom.Document;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import javax.security.auth.Subject;
import javax.security.auth.callback.Callback;
import javax.security.auth.callback.CallbackHandler;
import javax.security.auth.callback.NameCallback;
import javax.security.auth.callback.PasswordCallback;
import javax.security.auth.callback.UnsupportedCallbackException;
import javax.security.auth.login.LoginException;
import javax.security.auth.spi.LoginModule;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.File;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * To use Apache Tomcat's conf/tomcat-users.xml user database as JAAS {@link javax.security.auth.login.LoginContext},
 * so hawtio can use that for its {@link io.hawt.web.AuthenticationFilter}.
 */
public class TomcatUserDatabaseLoginContext implements LoginModule {

    private static final transient Logger LOG = LoggerFactory.getLogger(TomcatUserDatabaseLoginContext.class);
    private Subject subject;
    private CallbackHandler callbackHandler;
    private String fileName = "conf/tomcat-users.xml";
    private File file;
    private static final List<? extends Predicate<PasswordPair>> PASSWORD_CHECKS = Collections.unmodifiableList(
        Arrays.asList(
            new Predicate<PasswordPair>() {
                @Override
                public boolean evaluate(final PasswordPair passwordPair) {
                    return passwordPair.getFilePassword().equals(passwordPair.getSuppliedPassword());
                }
            },
            new Predicate<PasswordPair>() {
                @Override
                public boolean evaluate(final PasswordPair passwordPair) {
                    return passwordPair.getFilePassword().equals(DigestUtils.md5Hex(passwordPair.getSuppliedPassword()));
                }
            },
            new Predicate<PasswordPair>() {
                @Override
                public boolean evaluate(final PasswordPair passwordPair) {
                    return passwordPair.getFilePassword().equals(DigestUtils.sha256Hex(passwordPair.getSuppliedPassword()));
                }
            },
            new Predicate<PasswordPair>() {
                @Override
                public boolean evaluate(final PasswordPair passwordPair) {
                    return passwordPair.getFilePassword().equals(DigestUtils.shaHex(passwordPair.getSuppliedPassword()));
                }
            },
            new Predicate<PasswordPair>() {
                @Override
                public boolean evaluate(final PasswordPair passwordPair) {
                    return passwordPair.getFilePassword().equals(DigestUtils.sha512Hex(passwordPair.getSuppliedPassword()));
                }
            },
            new Predicate<PasswordPair>() {
                @Override
                public boolean evaluate(final PasswordPair passwordPair) {
                    return passwordPair.getFilePassword().equals(DigestUtils.sha384Hex(passwordPair.getSuppliedPassword()));
                }
            }
        )
    );

    @Override
    public void initialize(Subject subject, CallbackHandler callbackHandler, Map<String, ?> sharedState, Map<String, ?> options) {
        this.subject = subject;
        this.callbackHandler = callbackHandler;

        String base = System.getProperty("catalina.base", ".");
        LOG.debug("Using base directory: {}", base);
        this.file = new File(base, fileName);

        if (!file.exists()) {
            String msg = "Cannot find Apache Tomcat user database file: " + file;
            LOG.warn(msg);
            throw new IllegalStateException(msg);
        }
    }

    @Override
    public boolean login() throws LoginException {
        // get username and password
        Callback[] callbacks = new Callback[2];
        callbacks[0] = new NameCallback("username");
        callbacks[1] = new PasswordCallback("password", false);

        try {
            callbackHandler.handle(callbacks);
            String username = ((NameCallback) callbacks[0]).getName();
            char[] tmpPassword = ((PasswordCallback) callbacks[1]).getPassword();
            String password = new String(tmpPassword);
            ((PasswordCallback) callbacks[1]).clearPassword();

            LOG.debug("Getting user details for username {}", username);
            String[] user = getUserPasswordRole(username);
            if (user != null) {
                if (!passwordsMatch(new PasswordPair(user[1], password))) {
                    LOG.trace("Login denied due password did not match");
                    return false;
                }
                String[] roles = user[2].split(",");
                for (String role : roles) {
                    LOG.trace("User {} has role {}", username, role);
                    subject.getPrincipals().add(new TomcatPrincipal(role));
                }
            } else {
                LOG.trace("Login denied due user not found");
                return false;
            }
        } catch (UnsupportedCallbackException uce) {
            LoginException le = new LoginException("Error: " + uce.getCallback().toString()
                    + " not available to gather authentication information from the user");
            le.initCause(uce);
            throw le;
        } catch (Exception ioe) {
            LoginException le = new LoginException(ioe.toString());
            le.initCause(ioe);
            throw le;
        }

        return true;
    }

    protected boolean passwordsMatch(PasswordPair passwordPair) {
        for (Predicate<PasswordPair> passwordCheck : PASSWORD_CHECKS) {
            try {
                if (passwordCheck.evaluate(passwordPair)) {
                    return true;
                }
            } catch (Exception e) {
                LOG.warn("Login check failed with exception; continuing", e);
            }
        }
        return false;
    }

    @Override
    public boolean commit() throws LoginException {
        return true;
    }

    @Override
    public boolean abort() throws LoginException {
        return true;
    }

    @Override
    public boolean logout() throws LoginException {
        subject = null;
        callbackHandler = null;
        return true;
    }

    protected String[] getUserPasswordRole(String username) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        DocumentBuilder builder = factory.newDocumentBuilder();
        Document dom = builder.parse(file);

        NodeList users = dom.getElementsByTagName("user");
        for (int i = 0; i < users.getLength(); i++) {
            Node node = users.item(i);
            String nUsername = node.getAttributes().getNamedItem("username").getNodeValue();
            String nPassword = node.getAttributes().getNamedItem("password").getNodeValue();
            String nRoles = node.getAttributes().getNamedItem("roles").getNodeValue();
            if (username.equals(nUsername)) {
                return new String[]{username, nPassword, nRoles};
            }
        }
        return null;
    }

    private static class PasswordPair {
        private final String filePassword;
        private final String suppliedPassword;

        private PasswordPair(final String filePassword, final String suppliedPassword) {
            this.filePassword = filePassword;
            this.suppliedPassword = suppliedPassword;
        }

        public String getFilePassword() {
            return filePassword;
        }

        public String getSuppliedPassword() {
            return suppliedPassword;
        }
    }

}
