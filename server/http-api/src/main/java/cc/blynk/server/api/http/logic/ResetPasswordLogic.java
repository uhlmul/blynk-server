package cc.blynk.server.api.http.logic;

import cc.blynk.core.http.BaseHttpHandler;
import cc.blynk.core.http.MediaType;
import cc.blynk.core.http.Response;
import cc.blynk.core.http.annotation.*;
import cc.blynk.server.Holder;
import cc.blynk.server.api.http.pojo.TokenUser;
import cc.blynk.server.api.http.pojo.TokensPool;
import cc.blynk.server.core.BlockingIOProcessor;
import cc.blynk.server.core.dao.UserDao;
import cc.blynk.server.core.model.AppName;
import cc.blynk.server.core.model.auth.User;
import cc.blynk.server.notifications.mail.MailWrapper;
import cc.blynk.utils.FileLoaderUtil;
import cc.blynk.utils.validators.BlynkEmailValidator;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.UUID;

import static cc.blynk.core.http.Response.*;

/**
 * The Blynk project
 * Created by Andrew Zakordonets
 * Date : 12/05/2015.
 */
@Path("/")
@ChannelHandler.Sharable
public class ResetPasswordLogic extends BaseHttpHandler {

    private static final Logger log = LogManager.getLogger(ResetPasswordLogic.class);

    private final UserDao userDao;
    private final TokensPool tokensPool;
    private final String emailBody;
    private final MailWrapper mailWrapper;
    private final String resetPassUrl;
    private final String pageContent;
    private final BlockingIOProcessor blockingIOProcessor;

    private static final String RESET_PASS_STATIC_PATH = "static/reset/";

    public ResetPasswordLogic(Holder holder) {
        super(holder, "");
        this.userDao = holder.userDao;
        this.tokensPool = new TokensPool(60 * 60 * 1000);
        this.emailBody = FileLoaderUtil.readFileAsString(RESET_PASS_STATIC_PATH + "reset-email.html");
        this.mailWrapper = holder.mailWrapper;

        String host = holder.props.getServerHost();
        this.resetPassUrl = "http://" + host + "/landing?token=";
        this.pageContent = FileLoaderUtil.readFileAsString(RESET_PASS_STATIC_PATH + "enterNewPassword.html");
        this.blockingIOProcessor = holder.blockingIOProcessor;
    }

    private static String generateToken() {
        return (UUID.randomUUID().toString() + UUID.randomUUID().toString()).replace("-", "");
    }

    @POST
    @Consumes(value = MediaType.APPLICATION_FORM_URLENCODED)
    @Path("resetPassword")
    public Response sendResetPasswordEmail(@Context ChannelHandlerContext ctx,
                                           @FormParam("email") String email,
                                           @FormParam("appName") String appName) {

        if (BlynkEmailValidator.isNotValidEmail(email)) {
            return badRequest(email + " email has not valid format.");
        }

        final String trimmedEmail = email.trim().toLowerCase();
        appName = (appName == null ? AppName.BLYNK : appName);

        User user = userDao.getByName(trimmedEmail, appName);

        if (user == null) {
            return badRequest("Sorry, this account is not exists.");
        }

        String token = generateToken();
        log.info("{} trying to reset pass.", trimmedEmail);

        TokenUser userToken = new TokenUser(trimmedEmail, appName);
        tokensPool.addToken(token, userToken);
        String message = emailBody.replace("{RESET_URL}", resetPassUrl + token);
        log.info("Sending token to {} address", trimmedEmail);

        blockingIOProcessor.execute(() -> {
            Response response;
            try {
                mailWrapper.sendHtml(trimmedEmail, "Password reset request for Blynk app.", message);
                log.info("{} mail sent.", trimmedEmail);
                response = ok("Email was sent.");
            } catch (Exception e) {
                log.info("Error sending mail for {}. Reason : {}", trimmedEmail, e.getMessage());
                response = badRequest("Error sending reset email.");
            }
            ctx.writeAndFlush(response);
        });

        return noResponse();
    }

    @GET
    @Path("landing")
    public Response generateResetPage(@QueryParam("token") String token) {
        TokenUser user = tokensPool.getUser(token);
        if (user == null) {
            return badRequest("Your token was not found or it is outdated. Please try again.");
        }

        log.info("{} landed.", user.email);
        String page = pageContent.replace("{EMAIL}", user.email).replace("{TOKEN}", token);
        return ok(page, MediaType.TEXT_HTML);
    }

    @POST
    @Consumes(value = MediaType.APPLICATION_FORM_URLENCODED)
    @Path("updatePassword")
    public Response updatePassword(@FormParam("password") String password,
                                   @FormParam("token") String token) {
        TokenUser tokenUser = tokensPool.getUser(token);
        if (tokenUser == null) {
            return badRequest("Invalid token. Please repeat all steps.");
        }

        log.info("Resetting pass for {}", tokenUser.email);
        User user = userDao.getByName(tokenUser.email, tokenUser.appName);

        if (user == null) {
            log.warn("No user with email {}", tokenUser.email);
            return notFound();
        }

        user.pass = password;
        user.lastModifiedTs = System.currentTimeMillis();

        log.info("{} password was reset.", user.email);
        tokensPool.removeToken(token);
        return ok("Password was successfully reset.");
    }

}
