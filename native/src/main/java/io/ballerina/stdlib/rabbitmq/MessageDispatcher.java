/*
 * Copyright (c) 2019, WSO2 Inc. (http://www.wso2.org) All Rights Reserved.
 *
 * WSO2 Inc. licenses this file to you under the Apache License,
 * Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package io.ballerina.stdlib.rabbitmq;

import com.rabbitmq.client.AMQP;
import com.rabbitmq.client.AlreadyClosedException;
import com.rabbitmq.client.Channel;
import com.rabbitmq.client.DefaultConsumer;
import com.rabbitmq.client.Envelope;
import io.ballerina.runtime.api.Runtime;
import io.ballerina.runtime.api.concurrent.StrandMetadata;
import io.ballerina.runtime.api.creators.ValueCreator;
import io.ballerina.runtime.api.types.MethodType;
import io.ballerina.runtime.api.types.ObjectType;
import io.ballerina.runtime.api.types.Parameter;
import io.ballerina.runtime.api.types.Type;
import io.ballerina.runtime.api.utils.StringUtils;
import io.ballerina.runtime.api.utils.TypeUtils;
import io.ballerina.runtime.api.values.BError;
import io.ballerina.runtime.api.values.BMap;
import io.ballerina.runtime.api.values.BObject;
import io.ballerina.runtime.api.values.BString;
import io.ballerina.runtime.observability.ObservabilityConstants;
import io.ballerina.runtime.observability.ObserveUtils;
import io.ballerina.stdlib.rabbitmq.observability.RabbitMQMetricsUtil;
import io.ballerina.stdlib.rabbitmq.observability.RabbitMQObservabilityConstants;
import io.ballerina.stdlib.rabbitmq.observability.RabbitMQObserverContext;
import io.ballerina.stdlib.rabbitmq.util.ModuleUtils;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Semaphore;

import static io.ballerina.runtime.api.types.TypeTags.INTERSECTION_TAG;
import static io.ballerina.runtime.api.types.TypeTags.OBJECT_TYPE_TAG;
import static io.ballerina.runtime.api.types.TypeTags.RECORD_TYPE_TAG;
import static io.ballerina.runtime.api.constants.RuntimeConstants.ORG_NAME_SEPARATOR;
import static io.ballerina.runtime.api.constants.RuntimeConstants.VERSION_SEPARATOR;
import static io.ballerina.runtime.api.utils.TypeUtils.getReferredType;
import static io.ballerina.stdlib.rabbitmq.RabbitMQConstants.CONSTRAINT_VALIDATION;
import static io.ballerina.stdlib.rabbitmq.RabbitMQConstants.FUNC_ON_ERROR;
import static io.ballerina.stdlib.rabbitmq.RabbitMQConstants.FUNC_ON_MESSAGE;
import static io.ballerina.stdlib.rabbitmq.RabbitMQConstants.FUNC_ON_REQUEST;
import static io.ballerina.stdlib.rabbitmq.RabbitMQConstants.IS_ANYDATA_MESSAGE;
import static io.ballerina.stdlib.rabbitmq.RabbitMQConstants.ORG_NAME;
import static io.ballerina.stdlib.rabbitmq.RabbitMQConstants.PARAM_ANNOTATION_PREFIX;
import static io.ballerina.stdlib.rabbitmq.RabbitMQConstants.PARAM_PAYLOAD_ANNOTATION_NAME;
import static io.ballerina.stdlib.rabbitmq.RabbitMQConstants.RABBITMQ;
import static io.ballerina.stdlib.rabbitmq.RabbitMQConstants.TYPE_CHECKER_OBJECT_NAME;
import static io.ballerina.stdlib.rabbitmq.RabbitMQUtils.createAndPopulateMessageRecord;
import static io.ballerina.stdlib.rabbitmq.RabbitMQUtils.createPayload;
import static io.ballerina.stdlib.rabbitmq.RabbitMQUtils.getElementTypeDescFromArrayTypeDesc;
import static io.ballerina.stdlib.rabbitmq.RabbitMQUtils.returnErrorValue;
import static io.ballerina.stdlib.rabbitmq.RabbitMQUtils.validateConstraints;

/**
 * Handles and dispatched messages with data binding.
 *
 * @since 0.995
 */
public class MessageDispatcher {
    private final String consumerTag;
    private final Channel channel;
    private final boolean autoAck;
    private final BObject service;
    private final String queueName;
    private final BObject listenerObj;
    private final Runtime runtime;

    public MessageDispatcher(BObject service, Channel channel, boolean autoAck, Runtime runtime,
                             BObject listener) {
        this.channel = channel;
        this.autoAck = autoAck;
        this.service = service;
        this.queueName = getQueueNameFromConfig(service);
        this.consumerTag = TypeUtils.getType(service).getName();
        this.runtime = runtime;
        this.listenerObj = listener;
    }

    private String getQueueNameFromConfig(BObject service) {
        if (service.getNativeData(RabbitMQConstants.QUEUE_NAME.getValue()) != null) {
            return (String) service.getNativeData(RabbitMQConstants.QUEUE_NAME.getValue());
        } else {
            ObjectType serviceType = (ObjectType) TypeUtils.getReferredType(TypeUtils.getType(service));
            @SuppressWarnings("unchecked")
            BMap<BString, Object> serviceConfig = (BMap<BString, Object>) serviceType
                    .getAnnotation(StringUtils.fromString(ModuleUtils.getModule().getOrg() + ORG_NAME_SEPARATOR
                                                                  + ModuleUtils.getModule().getName() +
                                                                  VERSION_SEPARATOR
                                                                  + ModuleUtils.getModule().getMajorVersion() + ":"
                                                                  + RabbitMQConstants.SERVICE_CONFIG));
            return serviceConfig.getStringValue(RabbitMQConstants.ALIAS_QUEUE_NAME).getValue();
        }
    }

    /**
     * Start receiving messages and dispatch the messages to the attached service.
     *
     * @param listener Listener object value.
     */
    public void receiveMessages(BObject listener) {
        DefaultConsumer consumer = new DefaultConsumer(channel) {
            @Override
            public void handleDelivery(String consumerTag,
                                       Envelope envelope,
                                       AMQP.BasicProperties properties,
                                       byte[] body) {
                handleDispatch(body, envelope, properties);
            }
        };
        try {
            channel.basicConsume(queueName, autoAck, consumerTag, consumer);
        } catch (IOException exception) {
            RabbitMQMetricsUtil.reportError(channel, RabbitMQObservabilityConstants.ERROR_TYPE_CONSUME);
            throw returnErrorValue("Error occurred while consuming messages; " +
                                                         exception.getMessage());
        }
        @SuppressWarnings("unchecked")
        ArrayList<BObject> startedServices =
                (ArrayList<BObject>) listener.getNativeData(RabbitMQConstants.STARTED_SERVICES);
        startedServices.add(service);
        service.addNativeData(RabbitMQConstants.QUEUE_NAME.getValue(), queueName);
    }

    private void handleDispatch(byte[] message, Envelope envelope, AMQP.BasicProperties properties) {
        CountDownLatch countDownLatch = new CountDownLatch(1);
        RabbitMQResourceCallback callback = new RabbitMQResourceCallback(countDownLatch, channel, queueName,
                message.length, properties.getReplyTo(), envelope.getExchange());
        try {
            if (properties.getReplyTo() != null && getAttachedFunctionType(service, FUNC_ON_REQUEST) != null) {
                MethodType onRequestFunction = getAttachedFunctionType(service, FUNC_ON_REQUEST);
                Type returnType = onRequestFunction.getReturnType();
                Object[] arguments = getResourceParameters(message, envelope, properties, onRequestFunction);
                executeResourceOnRequest(callback, returnType, arguments);
            } else {
                MethodType onMessageFunction = getAttachedFunctionType(service, FUNC_ON_MESSAGE);
                Type returnType = onMessageFunction.getReturnType();
                Object[] arguments = getResourceParameters(message, envelope, properties, onMessageFunction);
                executeResourceOnMessage(callback, returnType, arguments);
            }
            countDownLatch.await();
        } catch (InterruptedException | AlreadyClosedException | BError exception) {
            RabbitMQMetricsUtil.reportError(channel, RabbitMQObservabilityConstants.ERROR_TYPE_CONSUME);
            MethodType onErrorFunction = getAttachedFunctionType(service, FUNC_ON_ERROR);
            if (exception instanceof BError) {
                executeOnError(onErrorFunction, message, envelope, properties, (BError) exception);
            } else {
                executeOnError(onErrorFunction, message, envelope, properties,
                        returnErrorValue(exception.getMessage()));
            }
        }
    }

    private Object[] getResourceParameters(byte[] message, Envelope envelope, AMQP.BasicProperties properties,
                                           MethodType remoteFunction) {

        Parameter[] parameters = remoteFunction.getParameters();
        boolean callerExists = false;
        boolean messageExists = false;
        boolean payloadExists = false;
        boolean constraintValidation = (boolean) listenerObj.getNativeData(CONSTRAINT_VALIDATION);
        Object[] arguments = new Object[parameters.length];
        int index = 0;
        for (Parameter parameter : parameters) {
            Type referredType = getReferredType(parameter.type);
            switch (referredType.getTag()) {
                case OBJECT_TYPE_TAG:
                    if (callerExists) {
                        returnErrorValue("Invalid remote function signature");
                    }
                    callerExists = true;
                    arguments[index++] = getCallerBObject(envelope.getDeliveryTag());

                    break;
                case INTERSECTION_TAG:
                case RECORD_TYPE_TAG:
                    if (isMessageType(parameter, remoteFunction.getAnnotations())) {
                        if (messageExists) {
                            returnErrorValue("Invalid remote function signature");
                        }
                        messageExists = true;
                        Object record = createAndPopulateMessageRecord(message, envelope,
                                properties, referredType);
                        validateConstraints(record, getElementTypeDescFromArrayTypeDesc(ValueCreator
                                .createTypedescValue(parameter.type)), constraintValidation);
                        arguments[index++] = record;
                        break;
                    }
                    /*-fallthrough*/
                default:
                    if (payloadExists) {
                        returnErrorValue("Invalid remote function signature");
                    }
                    payloadExists = true;
                    Object value = createPayload(message, referredType);
                    validateConstraints(value, getElementTypeDescFromArrayTypeDesc(ValueCreator
                            .createTypedescValue(parameter.type)), constraintValidation);
                    arguments[index++] = value;
                    break;
            }
        }
        return arguments;
    }

    private BObject getCallerBObject(long deliveryTag) {
        BObject callerObj = ValueCreator.createObjectValue(ModuleUtils.getModule(),
                                                           RabbitMQConstants.CALLER_OBJECT);
        RabbitMQTransactionContext transactionContext =
                (RabbitMQTransactionContext) listenerObj.getNativeData(RabbitMQConstants.RABBITMQ_TRANSACTION_CONTEXT);
        callerObj.addNativeData(RabbitMQConstants.DELIVERY_TAG.getValue(), deliveryTag);
        callerObj.addNativeData(RabbitMQConstants.CHANNEL_NATIVE_OBJECT, channel);
        callerObj.addNativeData(RabbitMQConstants.ACK_MODE, autoAck);
        callerObj.addNativeData(RabbitMQConstants.ACK_STATUS, false);
        callerObj.addNativeData(RabbitMQConstants.RABBITMQ_TRANSACTION_CONTEXT, transactionContext);
        return callerObj;
    }

    private void executeResourceOnMessage(RabbitMQResourceCallback callback, Type returnType, Object... args) {
        executeResource(RabbitMQConstants.FUNC_ON_MESSAGE, callback, args);
    }

    private void executeResourceOnRequest(RabbitMQResourceCallback callback, Type returnType, Object... args) {
        executeResource(FUNC_ON_REQUEST, callback, args);
    }

    private void executeOnError(MethodType onErrorMethod, byte[] message, Envelope envelope,
                               AMQP.BasicProperties properties, BError bError) {
        executeResource(FUNC_ON_ERROR, null, createAndPopulateMessageRecord(message, envelope, properties,
                        getReferredType(onErrorMethod.getParameters()[0].type)), bError);
    }

    private void executeResource(String function, RabbitMQResourceCallback callback, Object... args) {
        ObjectType serviceType = (ObjectType) TypeUtils.getReferredType(TypeUtils.getType(service));
        Thread.startVirtualThread(() -> {
            Map<String, Object> properties = getProperties(function);
            if (ObserveUtils.isTracingEnabled()) {
                properties = getNewObserverContextInProperties();
            }
            boolean isConcurrentSafe = serviceType.isIsolated() && serviceType.isIsolated(function);
            StrandMetadata strandMetadata = new StrandMetadata(isConcurrentSafe, properties);
            try {
                Object result = runtime.callMethod(service, function, strandMetadata, args);
                if (callback != null) {
                    callback.notifySuccess(result);
                }
            } catch (BError bError) {
                if (callback != null) {
                    callback.notifyFailure(bError);
                }
                throw bError;
            }
        });
    }

    private boolean isMessageType(Parameter parameter, BMap<BString, Object> annotations) {
        if (annotations.containsKey(StringUtils.fromString(PARAM_ANNOTATION_PREFIX + parameter.name))) {
            BMap paramAnnotationMap = annotations.getMapValue(StringUtils.fromString(
                    PARAM_ANNOTATION_PREFIX + parameter.name));
            if (paramAnnotationMap.containsKey(PARAM_PAYLOAD_ANNOTATION_NAME)) {
                return false;
            }
        }
        return invokeIsAnydataMessageTypeMethod(getReferredType(parameter.type));
    }

    private boolean invokeIsAnydataMessageTypeMethod(Type paramType) {
        BObject client = ValueCreator.createObjectValue(ModuleUtils.getModule(), TYPE_CHECKER_OBJECT_NAME);
        Semaphore sem = new Semaphore(0);
        RabbitMQTypeCheckCallback messageTypeCheckCallback = new RabbitMQTypeCheckCallback(sem);
        StrandMetadata strandMetadata = new StrandMetadata(true, getProperties(IS_ANYDATA_MESSAGE));
        Thread.startVirtualThread(() -> {
            try {
                Object result = runtime.callMethod(client, IS_ANYDATA_MESSAGE, strandMetadata,
                        ValueCreator.createTypedescValue(paramType));
                messageTypeCheckCallback.notifySuccess(result);
            } catch (BError bError) {
                messageTypeCheckCallback.notifyFailure(bError);
                throw bError;
            }
        });
        try {
            sem.acquire();
        } catch (InterruptedException e) {
            throw returnErrorValue(e.getMessage());
        }
        return messageTypeCheckCallback.getIsMessageType();
    }

    private Map<String, Object> getNewObserverContextInProperties() {
        Map<String, Object> properties = new HashMap<>();
        RabbitMQObserverContext observerContext = new RabbitMQObserverContext(channel);
        observerContext.addTag(RabbitMQObservabilityConstants.TAG_QUEUE, queueName);
        properties.put(ObservabilityConstants.KEY_OBSERVER_CONTEXT, observerContext);
        return properties;
    }

    private static MethodType getAttachedFunctionType(BObject serviceObject, String functionName) {
        MethodType function = null;
        MethodType[] resourceFunctions = ((ObjectType) TypeUtils.getReferredType(
                TypeUtils.getType(serviceObject))).getMethods();
        for (MethodType resourceFunction : resourceFunctions) {
            if (functionName.equals(resourceFunction.getName())) {
                function = resourceFunction;
                break;
            }
        }
        return function;
    }

    public static Map<String, Object> getProperties(String resourceName) {
        Map<String, Object> properties = new HashMap<>();
        properties.put("moduleOrg", ORG_NAME);
        properties.put("moduleName", RABBITMQ);
        properties.put("moduleVersion", ModuleUtils.getModule().getMajorVersion());
        properties.put("parentFunctionName", resourceName);
        return properties;
    }
}
