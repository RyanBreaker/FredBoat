/*
 *
 * MIT License
 *
 * Copyright (c) 2017 Frederik Ar. Mikkelsen
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */

package fredboat.messaging.internal

import com.fredboat.sentinel.entities.Embed
import com.fredboat.sentinel.entities.IMessage
import com.fredboat.sentinel.entities.SendMessageResponse
import com.fredboat.sentinel.entities.embed
import fredboat.command.config.PrefixCommand
import fredboat.commandmeta.MessagingException
import fredboat.feature.I18n
import fredboat.sentinel.*
import fredboat.shared.constant.BotConstants
import fredboat.util.TextUtils
import kotlinx.coroutines.experimental.reactive.awaitSingle
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import reactor.core.publisher.Mono
import java.text.MessageFormat
import java.util.*
import javax.annotation.CheckReturnValue
import fredboat.feature.metrics.Metrics.successfulRestActions
import javax.annotation.Nonnull
import org.springframework.messaging.simp.SimpMessageHeaderAccessor.getUser







/**
 * Provides a context to whats going on. Where is it happening, who caused it?
 * Also home to a bunch of convenience methods
 */
abstract class Context {

    companion object {
        private val log: Logger = LoggerFactory.getLogger(Context::class.java)
    }

    abstract val textChannel: TextChannel
    abstract val guild: Guild
    abstract val member: Member
    abstract val user: User

    /**
     * Convenience property to get the prefix of the guild of this context.
     */
    val prefix: String
        get() = PrefixCommand.giefPrefix(guild)

    // ********************************************************************************
    //                         Internal context stuff
    // ********************************************************************************

    private var i18n: ResourceBundle? = null


    // ********************************************************************************
    //                         Convenience reply methods
    // ********************************************************************************

    fun replyMono(message: String): Mono<SendMessageResponse> = textChannel.send(message)

    fun reply(message: String) {
        textChannel.send(message).subscribe()
    }

    fun replyMono(message: IMessage): Mono<SendMessageResponse> = textChannel.send(message)

    fun reply(message: IMessage) {
        textChannel.send(message).subscribe()
    }

    fun replyWithNameMono(message: String): Mono<SendMessageResponse> {
        return replyMono(TextUtils.prefaceWithName(member, message))
    }

    fun replyWithName(message: String) {
        reply(TextUtils.prefaceWithName(member, message))
    }

    fun replyWithMentionMono(message: String): Mono<SendMessageResponse> {
        return replyMono(TextUtils.prefaceWithMention(member, message))
    }

    fun replyWithMention(message: String) {
        reply(TextUtils.prefaceWithMention(member, message))
    }

    fun replyImageMono(url: String, message: String = ""): Mono<SendMessageResponse> {
        val embed = embedImage(url)
        embed.content = message
        return textChannel.send(embed)
    }

    fun replyImage(url: String, message: String = "") {
        replyImageMono(url, message).subscribe()
    }

    fun sendTyping() {
        textChannel.sendTyping()
    }

    /**
     * Privately DM the invoker
     */
    fun replyPrivate(message: String) {
        sendPrivate(user, message)
    }

    /**
     * Privately DM any user
     */
    fun sendPrivate(user: User, message: String) {
        if (user.bot) throw IllegalArgumentException("Cannot DM a bot user.")

        user.sendPrivate()

        user.openPrivateChannel().queue(
                { privateChannel ->
                    Metrics.successfulRestActions.labels("openPrivateChannel").inc()
                    CentralMessaging.message(privateChannel, message)
                            .success(onSuccess)
                            .failure(onFail)
                            .send(this)
                },
                onFail ?: CentralMessaging.NOOP_EXCEPTION_HANDLER //dun care logging about ppl that we cant message
        )
    }

    //TODO: Add support for in sentinel
    /*
    //checks whether we have the provided permissions for the channel of this context
    @CheckReturnValue
    public boolean hasPermissions(Permission... permissions) {
        return hasPermissions(getTextChannel(), permissions);
    }

    //checks whether we have the provided permissions for the provided channel
    @CheckReturnValue
    public boolean hasPermissions(@Nonnull TextChannel tc, Permission... permissions) {
        return getGuild().getSelfMember().hasPermission(tc, permissions);
    }*/

    /**
     * @return true if we the bot have all the provided permissions, false if not. Also informs the invoker about the
     * missing permissions for the bot, given there is a channel to reply in.
     */
    suspend fun checkSelfPermissionsWithFeedback(permissions: IPermissionSet): Boolean {
        val result = Sentinel.INSTANCE.checkPermissions(guild, guild.selfMember, permissions).awaitSingle()

        if (result.passed) return true
        if (result.missingEntityFault) return false // Error

        val builder = StringBuilder()
        PermissionSet(result.missingPermissions).asList().forEach{
            builder.append(it.uiName).append("\"**, **")
        }
        reply("${i18n("permissionMissingBot")} **$builder**")
        return false
    }

    /**
     * @return true if the invoker has all the provided permissions, false if not. Also informs the invoker about the
     * missing permissions, given there is a channel to reply in.
     */
    suspend fun checkInvokerPermissionsWithFeedback(permissions: IPermissionSet): Boolean {
        val result = Sentinel.INSTANCE.checkPermissions(guild, member, permissions).awaitSingle()

        if (result.passed) return true
        if (result.missingEntityFault) return false // Error

        val builder = StringBuilder()
        PermissionSet(result.missingPermissions).asList().forEach{
            builder.append(it.uiName).append("\"**, **")
        }
        reply("${i18n("permissionMissingInvoker")} **$builder**")
        return false
    }

    /**
     * Return a single translated string.
     *
     * @param key Key of the i18n string.
     * @return Formatted i18n string, or a default language string if i18n is not found.
     */
    @CheckReturnValue
    fun i18n(key: String): String {
        return if (getI18n().containsKey(key)) {
            getI18n().getString(key)
        } else {
            log.warn("Missing language entry for key {} in language {}", key, I18n.getLocale(guild).code)
            I18n.DEFAULT.props.getString(key)
        }
    }

    /**
     * Return a translated string with applied formatting.
     *
     * @param key Key of the i18n string.
     * @param params Parameter(s) to be apply into the i18n string.
     * @return Formatted i18n string.
     */
    @CheckReturnValue
    fun i18nFormat(key: String, vararg params: Any): String {
        if (params.isEmpty()) {
            log.warn("Context#i18nFormat() called with empty or null params, this is likely a bug.",
                    MessagingException("a stack trace to help find the source"))
        }
        return try {
            MessageFormat.format(this.i18n(key), *params)
        } catch (e: IllegalArgumentException) {
            log.warn("Failed to format key '{}' for language '{}' with following parameters: {}",
                    key, getI18n().baseBundleName, params, e)
            //fall back to default props
            MessageFormat.format(I18n.DEFAULT.props.getString(key), *params)
        }

    }

    fun getI18n(): ResourceBundle {
        var result = i18n
        if (result == null) {
            result = I18n.get(guild)
            i18n = result
        }
        return result
    }

    private fun embedImage(url: String): Embed = embed {
        color = BotConstants.FREDBOAT_COLOR.rgb
        image = url
    }
}
