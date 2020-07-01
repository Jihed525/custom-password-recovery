/*
 * Copyright (C) 2015 eXo Platform SAS.
 *
 * This is free software; you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License as
 * published by the Free Software Foundation; either version 2.1 of
 * the License, or (at your option) any later version.
 *
 * This software is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this software; if not, write to the Free
 * Software Foundation, Inc., 51 Franklin St, Fifth Floor, Boston, MA
 * 02110-1301 USA, or see the FSF site: http://www.fsf.org.
 */

package org.exoplatform.custom.forgotpassword;

import org.exoplatform.commons.utils.I18N;
import org.exoplatform.container.PortalContainer;
import org.exoplatform.portal.Constants;
import org.exoplatform.services.mail.MailService;
import org.exoplatform.services.mail.Message;
import org.exoplatform.services.organization.OrganizationService;
import org.exoplatform.services.organization.User;
import org.exoplatform.services.organization.UserProfile;
import org.exoplatform.services.resources.LocaleContextInfo;
import org.exoplatform.services.resources.ResourceBundleService;
import org.exoplatform.web.WebAppController;
import org.exoplatform.web.controller.QualifiedName;
import org.exoplatform.web.controller.router.Router;
import org.exoplatform.web.login.recovery.PasswordRecoveryHandler;
import org.exoplatform.web.login.recovery.PasswordRecoveryServiceImpl;
import org.exoplatform.web.security.security.RemindPasswordTokenService;
import org.gatein.common.logging.Logger;
import org.gatein.common.logging.LoggerFactory;
import org.gatein.wci.security.Credentials;

import javax.servlet.http.HttpServletRequest;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class CustomPasswordRecoveryServiceImpl extends PasswordRecoveryServiceImpl {

    protected static Logger log = LoggerFactory.getLogger(CustomPasswordRecoveryServiceImpl.class);

    private final OrganizationService orgService;
    private final MailService mailService;
    private final ResourceBundleService bundleService;
    private final RemindPasswordTokenService remindPasswordTokenService;
    private final WebAppController webController;

    public CustomPasswordRecoveryServiceImpl(OrganizationService orgService, MailService mailService, ResourceBundleService bundleService, RemindPasswordTokenService remindPasswordTokenService, WebAppController controller) {
        super(orgService, mailService, bundleService, remindPasswordTokenService, controller);
        this.orgService = orgService;
        this.mailService = mailService;
        this.bundleService = bundleService;
        this.remindPasswordTokenService = remindPasswordTokenService;
        this.webController = controller;
    }

    @Override
    public boolean sendRecoverPasswordEmail(User user, Locale defaultLocale, HttpServletRequest req) {
        if (user == null) {
            throw new IllegalArgumentException("User or Locale must not be null");
        }

        Locale locale = getLocaleOfUser(user.getUserName(), defaultLocale);

        PortalContainer container = PortalContainer.getCurrentInstance(req.getServletContext());

        ResourceBundle bundle = bundleService.getResourceBundle(bundleService.getSharedResourceBundleNames(), locale);

        Credentials credentials = new Credentials(user.getUserName(), "");
        String tokenId = remindPasswordTokenService.createToken(credentials);

        Router router = webController.getRouter();
        Map<QualifiedName, String> params = new HashMap<QualifiedName, String>();
        params.put(WebAppController.HANDLER_PARAM, PasswordRecoveryHandler.NAME);
        params.put(PasswordRecoveryHandler.TOKEN, tokenId);
        params.put(PasswordRecoveryHandler.LANG, I18N.toTagIdentifier(locale));

        StringBuilder url = new StringBuilder();
        url.append(req.getScheme()).append("://").append(req.getServerName());
        if (req.getServerPort() != 80 && req.getServerPort() != 443) {
            url.append(':').append(req.getServerPort());
        }
        url.append(container.getPortalContext().getContextPath());
        url.append(router.render(params));

        String emailBody = buildEmailBody(user, bundle, url.toString());
        String emailSubject = getEmailSubject(user, bundle);

        String senderName = getSenderName();
        String from = getSenderEmail();
        if (senderName != null && !senderName.trim().isEmpty()) {
            from = senderName + " <" + from + ">";
        }

        Message message = new Message();
        message.setFrom(from);
        message.setTo(user.getEmail());
        message.setSubject(emailSubject);
        message.setBody(emailBody);
        message.setMimeType("text/html");

        try {
            mailService.sendMessage(message);
        } catch (Exception ex) {
            log.error("Failure to send recover password email", ex);
            return false;
        }

        return true;
    }

    private Locale getLocaleOfUser(String username, Locale defLocale) {
        try {
            UserProfile profile = orgService.getUserProfileHandler().findUserProfileByName(username);
            String lang = profile == null ? null : profile.getUserInfoMap().get(Constants.USER_LANGUAGE);
            return (lang != null) ? LocaleContextInfo.getLocale(lang) : defLocale;
        } catch (Exception ex) { //NOSONAR
            log.debug("Can not load user profile language", ex);
            return defLocale;
        }
    }

    private String buildEmailBody(User user, ResourceBundle bundle, String link) {
        String content;
        InputStream input = this.getClass().getClassLoader().getResourceAsStream("conf/forgot_password_email_template.html");
        if (input == null) {
            content = "";
        } else {
            content = resolveLanguage(input, bundle);
        }

        content = content.replaceAll("\\$\\{FIRST_NAME\\}", user.getFirstName());
        content = content.replaceAll("\\$\\{USERNAME\\}", user.getUserName());
        content = content.replaceAll("\\$\\{RESET_PASSWORD_LINK\\}", link);

        return content;
    }

    private String resolveLanguage(InputStream input, ResourceBundle bundle) {
        // Read from input string
        StringBuffer content = new StringBuffer();
        try {
            BufferedReader reader = new BufferedReader(new InputStreamReader(input));
            String line;
            while ((line = reader.readLine()) != null) {
                if (content.length() > 0) {
                    content.append("\n");
                }
                resolveLanguage(content, line, bundle);
            }
        } catch (IOException ex) {
            log.error(ex);
        }
        return content.toString();
    }

    private static final Pattern PATTERN = Pattern.compile("&\\{([a-zA-Z0-9\\.]+)\\}");
    private void resolveLanguage(StringBuffer sb, String input, ResourceBundle bundle) {
        Matcher matcher = PATTERN.matcher(input);
        while (matcher.find()) {
            String key = matcher.group(1);
            String resource;
            try {
                resource = bundle.getString(key);
            } catch (MissingResourceException ex) {
                resource = key;
            }
            matcher.appendReplacement(sb, resource);
        }
        matcher.appendTail(sb);
    }

}
