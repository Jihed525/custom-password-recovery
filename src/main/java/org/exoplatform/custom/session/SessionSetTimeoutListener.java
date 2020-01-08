package org.exoplatform.custom.session;


import javax.servlet.http.HttpSessionEvent;

import org.exoplatform.container.PortalContainer;
import org.exoplatform.container.xml.InitParams;
import org.exoplatform.services.listener.Event;
import org.exoplatform.services.listener.Listener;
import org.exoplatform.services.log.ExoLogger;
import org.exoplatform.services.log.Log;

public class SessionSetTimeoutListener extends Listener<PortalContainer, HttpSessionEvent> {

    private static Log log = ExoLogger.getLogger(SessionSetTimeoutListener.class);

    /**
     * Default value 30 minutes
     */
    private static int sessionTimeoutSeconds = 60 / 10;

    public SessionSetTimeoutListener(InitParams params) {
        if (params.containsKey("web.session.timeout.minutes") && ! params.getValueParam("web.session.timeout.minutes").getValue().isEmpty()) {
            sessionTimeoutSeconds = Integer.parseInt(params.getValueParam("web.session.timeout.minutes").getValue()) * 60;

        } else {
            log.info("Init param 'web.session.timeout.minutes' is not set, use default = " + sessionTimeoutSeconds);
        }
    }

    @Override
    public void onEvent(Event<PortalContainer, HttpSessionEvent> event) throws Exception {

        event.getData().getSession().setMaxInactiveInterval(sessionTimeoutSeconds);
    }
}
