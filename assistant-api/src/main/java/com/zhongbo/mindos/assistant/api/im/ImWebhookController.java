package com.zhongbo.mindos.assistant.api.im;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.util.DigestUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import javax.xml.parsers.DocumentBuilderFactory;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.logging.Level;
import java.util.logging.Logger;

@RestController
@RequestMapping("/api/im")
public class ImWebhookController {

    private static final Logger LOGGER = Logger.getLogger(ImWebhookController.class.getName());

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final ImGatewayService imGatewayService;

    @Value("${mindos.im.enabled:false}")
    private boolean imEnabled;

    @Value("${mindos.im.feishu.enabled:false}")
    private boolean feishuEnabled;

    @Value("${mindos.im.dingtalk.enabled:false}")
    private boolean dingtalkEnabled;

    @Value("${mindos.im.wechat.enabled:false}")
    private boolean wechatEnabled;

    @Value("${mindos.im.feishu.verify-signature:true}")
    private boolean feishuVerifySignature;

    @Value("${mindos.im.dingtalk.verify-signature:true}")
    private boolean dingtalkVerifySignature;

    @Value("${mindos.im.wechat.verify-signature:true}")
    private boolean wechatVerifySignature;

    @Value("${mindos.im.feishu.secret:}")
    private String feishuSecret;

    @Value("${mindos.im.dingtalk.secret:}")
    private String dingtalkSecret;

    @Value("${mindos.im.dingtalk.reply-timeout-ms:2500}")
    private long dingtalkReplyTimeoutMs;

    @Value("${mindos.im.dingtalk.reply-max-chars:1200}")
    private int dingtalkReplyMaxChars;

    @Value("${mindos.im.wechat.token:}")
    private String wechatToken;

    public ImWebhookController(ImGatewayService imGatewayService) {
        this.imGatewayService = imGatewayService;
    }

    @PostMapping(value = "/feishu/events", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Map<String, Object>> feishuEvents(
            @RequestBody String rawBody,
            @RequestHeader(value = "X-Lark-Request-Timestamp", required = false) String timestamp,
            @RequestHeader(value = "X-Lark-Request-Nonce", required = false) String nonce,
            @RequestHeader(value = "X-Lark-Signature", required = false) String signature) {
        if (!isPlatformEnabled(feishuEnabled)) {
            return disabled();
        }
        if (feishuVerifySignature && !verifyFeishu(rawBody, timestamp, nonce, signature)) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("message", "invalid signature"));
        }

        try {
            JsonNode root = objectMapper.readTree(rawBody);
            if (root.has("challenge")) {
                return ResponseEntity.ok(Map.of("challenge", root.path("challenge").asText("")));
            }
            if (!"im.message.receive_v1".equals(root.path("header").path("event_type").asText(""))) {
                return ResponseEntity.ok(Map.of("message", "ignored"));
            }

            JsonNode event = root.path("event");
            JsonNode message = event.path("message");
            String messageType = message.path("message_type").asText("");
            if (!"text".equalsIgnoreCase(messageType)) {
                return ResponseEntity.ok(Map.of("message", "only text is supported"));
            }
            String text = extractFeishuText(message.path("content").asText(""));
            String senderId = event.path("sender").path("sender_id").path("open_id").asText("");
            String chatId = message.path("chat_id").asText("");
            String reply = ImReplySanitizer.sanitize(imGatewayService.chat(ImPlatform.FEISHU, senderId, chatId, text));

            Map<String, Object> content = new LinkedHashMap<>();
            content.put("text", reply);
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("msg_type", "text");
            payload.put("content", content);
            return ResponseEntity.ok(payload);
        } catch (Exception ex) {
            return ResponseEntity.ok(Map.of("message", "accepted"));
        }
    }

    @PostMapping(value = "/dingtalk/events", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Map<String, Object>> dingtalkEvents(
            @RequestBody(required = false) String rawBody,
            @RequestParam(value = "timestamp", required = false) String timestamp,
            @RequestParam(value = "sign", required = false) String sign) {
        if (!isPlatformEnabled(dingtalkEnabled)) {
            return disabled();
        }
        if (dingtalkVerifySignature && !verifyDingtalk(timestamp, sign)) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("message", "invalid signature"));
        }

        // DingTalk validation probes may send empty or non-standard payloads.
        String bodyForLog = rawBody == null ? "" : rawBody;
        LOGGER.fine("DingTalk event received, bodyLength=" + bodyForLog.length());

        String text = "";
        String senderId = "";
        String chatId = "";
        try {
            JsonNode body = rawBody == null || rawBody.isBlank() ? objectMapper.createObjectNode() : objectMapper.readTree(rawBody);
            JsonNode textNode = body.path("text");
            text = textNode.path("content").asText("");
            senderId = body.path("senderId").asText("");
            chatId = body.path("conversationId").asText("");
        } catch (Exception ignored) {
            // Keep empty defaults for malformed payload; still return 200 for platform checks.
        }

        String reply = resolveDingtalkReply(senderId, chatId, text);

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("msgtype", "text");
        payload.put("text", Map.of("content", reply));
        return ResponseEntity.ok(payload);
    }

    @GetMapping(value = "/wechat/events", produces = MediaType.TEXT_PLAIN_VALUE)
    public ResponseEntity<String> wechatVerify(@RequestParam("signature") String signature,
                                               @RequestParam("timestamp") String timestamp,
                                               @RequestParam("nonce") String nonce,
                                               @RequestParam("echostr") String echostr) {
        if (!isPlatformEnabled(wechatEnabled)) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body("wechat disabled");
        }
        if (wechatVerifySignature && !verifyWechat(signature, timestamp, nonce)) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body("invalid signature");
        }
        return ResponseEntity.ok(echostr);
    }

    @PostMapping(value = "/wechat/events", consumes = MediaType.TEXT_XML_VALUE, produces = MediaType.TEXT_XML_VALUE)
    public ResponseEntity<String> wechatEvents(@RequestParam("signature") String signature,
                                               @RequestParam("timestamp") String timestamp,
                                               @RequestParam("nonce") String nonce,
                                               @RequestBody String xmlBody) {
        if (!isPlatformEnabled(wechatEnabled)) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body("<xml><Content>wechat disabled</Content></xml>");
        }
        if (wechatVerifySignature && !verifyWechat(signature, timestamp, nonce)) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body("<xml><Content>invalid signature</Content></xml>");
        }
        try {
            WechatMessage message = parseWechatMessage(xmlBody);
            if (!"text".equalsIgnoreCase(message.msgType)) {
                return ResponseEntity.ok("success");
            }
            String reply = ImReplySanitizer.sanitize(imGatewayService.chat(ImPlatform.WECHAT, message.fromUser, message.toUser, message.content));
            String responseXml = "<xml>"
                    + "<ToUserName><![CDATA[" + message.fromUser + "]]></ToUserName>"
                    + "<FromUserName><![CDATA[" + message.toUser + "]]></FromUserName>"
                    + "<CreateTime>" + (System.currentTimeMillis() / 1000) + "</CreateTime>"
                    + "<MsgType><![CDATA[text]]></MsgType>"
                    + "<Content><![CDATA[" + escapeCdata(reply) + "]]></Content>"
                    + "</xml>";
            return ResponseEntity.ok()
                    .contentType(new MediaType("text", "xml", StandardCharsets.UTF_8))
                    .body(responseXml);
        } catch (Exception ex) {
            return ResponseEntity.ok("success");
        }
    }

    private boolean isPlatformEnabled(boolean platformEnabled) {
        return imEnabled && platformEnabled;
    }

    private ResponseEntity<Map<String, Object>> disabled() {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(Map.of("message", "im integration disabled"));
    }

    private String resolveDingtalkReply(String senderId, String chatId, String text) {
        long timeoutMs = Math.max(300L, dingtalkReplyTimeoutMs);
        CompletableFuture<String> replyFuture = imGatewayService.chatAsync(ImPlatform.DINGTALK, senderId, chatId, text);
        try {
            return capForDingtalk(ImReplySanitizer.sanitize(replyFuture.get(timeoutMs, TimeUnit.MILLISECONDS)));
        } catch (TimeoutException ex) {
            replyFuture.cancel(true);
            LOGGER.warning("DingTalk reply timed out before webhook response: senderHash="
                    + safeHash(senderId) + ", chatHash=" + safeHash(chatId) + ", timeoutMs=" + timeoutMs);
            return ImReplySanitizer.TIMEOUT_IM_FALLBACK_REPLY;
        } catch (Exception ex) {
            LOGGER.log(Level.WARNING,
                    "DingTalk reply failed before webhook response: senderHash=" + safeHash(senderId)
                            + ", chatHash=" + safeHash(chatId),
                    ex);
            return ImReplySanitizer.FRIENDLY_IM_FALLBACK_REPLY;
        }
    }

    private String capForDingtalk(String reply) {
        if (reply == null) {
            return "";
        }
        int maxChars = Math.max(200, dingtalkReplyMaxChars);
        if (reply.length() <= maxChars) {
            return reply;
        }
        return reply.substring(0, maxChars - 1) + "…";
    }

    private String safeHash(String value) {
        if (value == null || value.isBlank()) {
            return "n/a";
        }
        return Integer.toHexString(value.trim().hashCode());
    }


    private boolean verifyFeishu(String rawBody, String timestamp, String nonce, String signature) {
        if (isBlank(feishuSecret)) {
            return false;
        }
        if (isBlank(timestamp) || isBlank(nonce) || isBlank(signature)) {
            return false;
        }
        String base = timestamp + nonce + feishuSecret + (rawBody == null ? "" : rawBody);
        String expected = DigestUtils.md5DigestAsHex(base.getBytes(StandardCharsets.UTF_8));
        return expected.equalsIgnoreCase(signature);
    }

    private boolean verifyDingtalk(String timestamp, String sign) {
        if (isBlank(dingtalkSecret)) {
            return false;
        }
        if (isBlank(timestamp) || isBlank(sign)) {
            return false;
        }
        try {
            String stringToSign = timestamp + "\n" + dingtalkSecret;
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(dingtalkSecret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] signData = mac.doFinal(stringToSign.getBytes(StandardCharsets.UTF_8));
            String encoded = URLEncoder.encode(java.util.Base64.getEncoder().encodeToString(signData), StandardCharsets.UTF_8);
            return encoded.equals(sign);
        } catch (Exception e) {
            return false;
        }
    }

    private boolean verifyWechat(String signature, String timestamp, String nonce) {
        if (isBlank(wechatToken)) {
            return false;
        }
        if (isBlank(signature) || isBlank(timestamp) || isBlank(nonce)) {
            return false;
        }
        try {
            String[] parts = new String[]{wechatToken, timestamp, nonce};
            Arrays.sort(parts);
            String joined = String.join("", parts);
            MessageDigest digest = MessageDigest.getInstance("SHA-1");
            byte[] hash = digest.digest(joined.getBytes(StandardCharsets.UTF_8));
            StringBuilder builder = new StringBuilder();
            for (byte value : hash) {
                builder.append(String.format("%02x", value));
            }
            return builder.toString().equalsIgnoreCase(signature);
        } catch (Exception e) {
            return false;
        }
    }

    private String extractFeishuText(String contentJson) {
        try {
            JsonNode content = objectMapper.readTree(contentJson);
            return content.path("text").asText("");
        } catch (Exception ex) {
            return "";
        }
    }

    private WechatMessage parseWechatMessage(String xmlBody) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setExpandEntityReferences(false);
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        var builder = factory.newDocumentBuilder();
        var document = builder.parse(new java.io.ByteArrayInputStream(xmlBody.getBytes(StandardCharsets.UTF_8)));
        String toUser = getTag(document, "ToUserName");
        String fromUser = getTag(document, "FromUserName");
        String msgType = getTag(document, "MsgType");
        String content = getTag(document, "Content");
        return new WechatMessage(toUser, fromUser, msgType, content);
    }

    private String getTag(org.w3c.dom.Document document, String tag) {
        var nodes = document.getElementsByTagName(tag);
        if (nodes.getLength() == 0) {
            return "";
        }
        return nodes.item(0).getTextContent();
    }

    private String escapeCdata(String text) {
        if (text == null) {
            return "";
        }
        return text.replace("]]>", "] ]>");
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private record WechatMessage(String toUser, String fromUser, String msgType, String content) {
    }
}

