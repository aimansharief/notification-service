package org.sunbird.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.apache.commons.collections.CollectionUtils;
import org.apache.commons.collections.MapUtils;
import org.apache.velocity.VelocityContext;
import org.apache.velocity.app.Velocity;
import org.apache.velocity.app.VelocityEngine;
import org.everit.json.schema.Schema;
import org.everit.json.schema.loader.SchemaLoader;
import org.json.JSONObject;
import org.sunbird.JsonKey;
import org.sunbird.common.exception.BaseException;
import org.sunbird.common.util.Notification;
import org.sunbird.dao.NotificationDao;
import org.sunbird.dao.NotificationDaoImpl;
import org.sunbird.common.message.IResponseMessage;
import org.sunbird.common.message.ResponseCode;
import org.sunbird.pojo.ActionData;
import org.sunbird.pojo.NotificationFeed;
import org.sunbird.pojo.NotificationV2Request;
import org.sunbird.request.LoggerUtil;
import org.sunbird.common.response.Response;

import java.io.IOException;
import java.io.StringWriter;
import java.sql.Timestamp;
import java.util.*;

public class NotificationServiceImpl implements NotificationService {
    private static LoggerUtil logger = new LoggerUtil(NotificationServiceImpl.class);
    private static NotificationService notificationService = null;
    private ObjectMapper mapper = new ObjectMapper();

    private static NotificationDao notificationDao = NotificationDaoImpl.getInstance();
    public static NotificationService getInstance() {
        if (notificationService == null) {
            notificationService = new NotificationServiceImpl();
        }
        return notificationService;
    }

    @Override
    public Map<String,Object> getTemplate(String actionType, Map<String,Object> reqContext) throws BaseException {

        Response response = notificationDao.getTemplateId(actionType,reqContext);
        if (null != response && MapUtils.isNotEmpty(response.getResult())) {
            List<Map<String, Object>> templateIdDetails =
                    (List<Map<String, Object>>) response.getResult().get(JsonKey.RESPONSE);
            if(CollectionUtils.isNotEmpty(templateIdDetails)){
                Map<String,Object> dbTemplateId = templateIdDetails.get(0);
                String templateId = (String) dbTemplateId.get(JsonKey.TEMPLATE_ID);
                Response responseObj = notificationDao.getTemplate(templateId, reqContext);
                if (null != responseObj && MapUtils.isNotEmpty(responseObj.getResult())) {
                    List<Map<String, Object>> templateDetails =
                            (List<Map<String, Object>>) responseObj.getResult().get(JsonKey.RESPONSE);
                    if(CollectionUtils.isNotEmpty(templateDetails)){
                        Map<String,Object> dbTemplate = templateDetails.get(0);
                        return dbTemplate;
                    }
                }
            }
        }
        return new HashMap<>();
    }

    @Override
    public void validateTemplate(Map<String, Object> paramObj, String templateSchema) throws BaseException {

        JSONObject jsonSchema = new JSONObject(templateSchema.toString());
        try {
            JSONObject jsonObject = new JSONObject(mapper.writeValueAsString(paramObj));
            Schema schema = SchemaLoader.load(jsonSchema);
            schema.validate(jsonObject);
        } catch (JsonProcessingException e) {
            logger.error("Error while validating template",e);
            throw new BaseException(IResponseMessage.INTERNAL_ERROR,e.getMessage(), ResponseCode.SERVER_ERROR.getCode());
        }

    }

    @Override
    public String transformTemplate(String templateData, Map<String, Object> paramObj) throws BaseException {
        VelocityEngine engine = new VelocityEngine();
        VelocityContext context =getContext(paramObj);
        Properties p = new Properties();
        p.setProperty("resource.loader", "class");
        p.setProperty(
                "class.resource.loader.class",
                "org.apache.velocity.runtime.resource.loader.ClasspathResourceLoader");
        StringWriter writer = null;
        String body = null;
        try {
            engine.init(p);
            writer = new StringWriter();
            Velocity.evaluate(context, writer, "log or null", templateData);
            return writer.toString();
        }catch (Exception ex){
            logger.error("Error while transforming template",ex);
            throw new BaseException(IResponseMessage.INTERNAL_ERROR,ex.getMessage(), ResponseCode.SERVER_ERROR.getCode());
        }
    }

    @Override
    public Response createNotificationFeed(NotificationV2Request request, Map<String,Object> reqContext) throws BaseException {
        List<String> ids = request.getIds();
        List<NotificationFeed> feedList = new ArrayList<>();

        for (String id: ids) {
            NotificationFeed feed = new NotificationFeed();
            feed.setId(UUID.randomUUID().toString());
            feed.setPriority(request.getPriority());
            feed.setStatus("unread");
            feed.setCategory((String) request.getAction().get(JsonKey.CATEGORY));
            feed.setCreatedBy((String) ((Map<String,Object>)request.getAction().get(JsonKey.CREATED_BY)).get(JsonKey.ID));
            feed.setCreatedOn(new Timestamp(Calendar.getInstance().getTime().getTime()));
            feed.setUserId(id);
            feed.setAction(getAction(request.getAction()));
            feedList.add(feed);
        }
        Response response = notificationDao.createNotificationFeed(feedList,reqContext);
        return response;
    }

    @Override
    public Response createV1NotificationFeed(Map<String,Object> notificationV1Req, Map<String,Object> reqContext) throws BaseException, JsonProcessingException {
        List<NotificationFeed> feedList = new ArrayList<>();
        List<String> ids = (List<String>)notificationV1Req.get(JsonKey.USER_ID);
        for (String id: ids) {
            NotificationFeed feed = new NotificationFeed();
            feed.setId(UUID.randomUUID().toString());
            feed.setPriority((Integer) notificationV1Req.get(JsonKey.PRIORITY));
            feed.setStatus("unread");
            feed.setCategory((String) notificationV1Req.get(JsonKey.CATEGORY));
            feed.setCreatedBy((String) notificationV1Req.get(JsonKey.CREATED_BY));
            feed.setCreatedOn(new Timestamp(Calendar.getInstance().getTime().getTime()));
            feed.setUserId(id);
            feed.setAction(new ObjectMapper().writeValueAsString((Map<String,Object>)notificationV1Req.get(JsonKey.DATA)));
            feed.setVersion("v1");
            feedList.add(feed);
        }

        Response response = notificationDao.createNotificationFeed(feedList,reqContext);
        return response;
    }

    @Override
    public Response deleteNotificationFeed(List<String> ids, String userId, String category, Map<String, Object> reqContext) throws BaseException, JsonProcessingException {
        List<NotificationFeed> feeds = new ArrayList<>();
        for (String feedId:ids) {
            NotificationFeed feed = new NotificationFeed();
            feed.setId(feedId);
            feed.setUserId(userId);
            feed.setCategory(category);
            feeds.add(feed);
        }
        if(CollectionUtils.isNotEmpty(feeds)) {
            return notificationDao.deleteUserFeed(feeds, reqContext);
        }else{
            throw new BaseException(IResponseMessage.INTERNAL_ERROR,IResponseMessage.Message.INTERNAL_ERROR, ResponseCode.SERVER_ERROR.getCode());
        }
    }


    private String getAction(Map<String,Object> action) throws BaseException {
        try {
            return mapper.writeValueAsString(action);
        }catch (JsonProcessingException ex){
            logger.error("Error while action processing",ex);
            throw new BaseException(IResponseMessage.INTERNAL_ERROR,ex.getMessage(), ResponseCode.SERVER_ERROR.getCode());

        }
    }

    @Override
    public List<Map<String, Object>> readNotificationFeed(String userId, Map<String,Object> reqContext) throws BaseException, IOException {

        Response response = notificationDao.readNotificationFeed(userId,reqContext);
        List<Map<String, Object>> notifications = new ArrayList<>();
        if (null != response && MapUtils.isNotEmpty(response.getResult())) {
            notifications = (List<Map<String, Object>>) response.getResult().get(JsonKey.RESPONSE);
            if(CollectionUtils.isNotEmpty(notifications)){
                Iterator<Map<String,Object>> notifyItr = notifications.iterator();
                while (notifyItr.hasNext()) {
                    Map<String,Object> notification = notifyItr.next();
                    if(JsonKey.V1.equals(notification.get(JsonKey.VERSION))){
                        notifyItr.remove();
                    }else{
                        String actionStr = (String) notification.get(JsonKey.ACTION);
                        Map<String,Object> actionData= null;
                        if(actionStr != null){
                            ObjectMapper mapper = new ObjectMapper();
                            actionData = mapper.readValue(actionStr,Map.class);
                        }
                        notification.put(JsonKey.ACTION,actionData);
                    }

                }
            }
        }
        return notifications;
    }

    @Override
    public List<Map<String, Object>> readV1NotificationFeed(String userId, Map<String,Object> reqContext) throws BaseException, IOException {

        Response response = notificationDao.readNotificationFeed(userId,reqContext);
        List<Map<String, Object>> notifications = new ArrayList<>();
        if (null != response && MapUtils.isNotEmpty(response.getResult())) {
            notifications = (List<Map<String, Object>>) response.getResult().get(JsonKey.RESPONSE);
            if(CollectionUtils.isNotEmpty(notifications)){
                Iterator<Map<String,Object>> notifyItr = notifications.iterator();
                while (notifyItr.hasNext()) {
                    Map<String,Object> notification = notifyItr.next();
                    if(!JsonKey.V1.equals(notification.get(JsonKey.VERSION))){
                       notifyItr.remove();
                    }else {
                        String actionStr = (String) notification.get(JsonKey.ACTION);
                        ObjectMapper mapper = new ObjectMapper();
                        Map actionData = mapper.readValue(actionStr, Map.class);
                        notification.put(JsonKey.DATA, actionData);
                        notification.remove(JsonKey.ACTION);
                    }
                }
            }
        }
        return notifications;
    }

    @Override
    public Response updateNotificationFeed( List<Map<String,Object>>  feeds, Map<String,Object> reqContext) throws BaseException {
        return notificationDao.updateNotificationFeed(feeds, reqContext);
    }

    private VelocityContext getContext(Map<String, Object> paramObj) {
        VelocityContext context = new VelocityContext();
        for (Map.Entry<String,Object> itr: paramObj.entrySet()){
            context.put(itr.getKey(),itr.getValue());
        }
        return context;
    }
}
