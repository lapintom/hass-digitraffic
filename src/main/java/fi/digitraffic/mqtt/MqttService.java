package fi.digitraffic.mqtt;

import com.google.gson.*;
import fi.digitraffic.Config;
import fi.digitraffic.hass.SensorValueService;
import fi.digitraffic.mqtt.model.MqttSensorValue;
import fi.digitraffic.mqtt.model.MqttConfig;
import org.eclipse.paho.client.mqttv3.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.time.ZonedDateTime;
import java.util.Map;
import java.util.UUID;

import static fi.digitraffic.mqtt.ServerConfig.*;
import static java.net.HttpURLConnection.HTTP_OK;

@Component
public class MqttService {
    private static final Logger LOG = LoggerFactory.getLogger(MqttService.class);

    private final Gson gson = new GsonBuilder().registerTypeAdapter(ZonedDateTime.class, (JsonDeserializer<ZonedDateTime>) (json, type, jsonDeserializationContext) -> ZonedDateTime.parse(json.getAsJsonPrimitive().getAsString())).create();

    private final SensorValueService sensorValueService;
    private final MqttConfigService mqttConfigService;

    public MqttService(final SensorValueService sensorValueService, final MqttConfigService mqttConfigService) throws MqttException {
        this.sensorValueService = sensorValueService;
        this.mqttConfigService = mqttConfigService;

        initialize();
    }

    private void initialize() throws MqttException {
        final MqttConfig options = mqttConfigService.readAndValidate();

        if(options != null) {
            if(!options.getRoadConfigs().isEmpty()) {
                createClient(options.getRoadConfigs(), ServerConfig.ROAD, (message, config) -> handleRoadMessage(message, config));
            }
            if(!options.getSseConfigs().isEmpty()) {
                createClient(options.getSseConfigs(), ServerConfig.MARINE, (message, config) -> handleSseMessage(message, config));
            }
        }
    }

    private interface MessageHandler {
        void handleMessage(final MqttMessage message, final Config.SensorConfig config) throws IOException;
    }

    private MqttCallback createCallBack(final Map<String, Config.SensorConfig> configMap, final IMqttClient client, final MessageHandler messageHandler) {
        return new MqttCallback() {
            @Override
            public void connectionLost(final Throwable cause) {
                LOG.error("connection lost", cause);
                try {
                    client.reconnect();
                } catch (final MqttException e) {
                    LOG.error("can't reconnect", e);
                }
            }

            @Override
            public void messageArrived(final String topic, final MqttMessage message) {
                try {
                    if(!topic.contains("status")) {
                        LOG.info("topic {} got message {}", topic, new String(message.getPayload()));

                        messageHandler.handleMessage(message, configMap.get(topic));
                    }
                } catch(final Exception e) {
                    LOG.error("error", e);
                }
            }

            @Override
            public void deliveryComplete(final IMqttDeliveryToken token) {

            }
        };
    }

    private void createClient(final Map<String, Config.SensorConfig> configs, final ServerConfig serverConfig, final MessageHandler messageHandler) throws MqttException {
        final String clientId = CLIENT_ID + UUID.randomUUID().toString();
        final IMqttClient client = new MqttClient(serverConfig.serverAddress, clientId);

        client.setCallback(createCallBack(configs, client, messageHandler));
        client.connect(setUpConnectionOptions());

        configs.keySet().forEach(topic -> {
            try {
                LOG.info("subscribing to {}", topic);
                client.subscribe(topic);
            } catch (final MqttException e) {
                LOG.error(String.format("Could not not subscribe to topic %s", topic), e);
            }
        });

        client.subscribe(serverConfig.statusTopic);

        LOG.info("Starting mqtt client " + serverConfig.serverAddress);
    }

    private void handleRoadMessage(final MqttMessage message, final Config.SensorConfig sensorConfig) {
        final MqttSensorValue wd = gson.fromJson(new String(message.getPayload()), MqttSensorValue.class);

        postSensorValue(sensorConfig.sensorName, wd.sensorValue, sensorConfig.unitOfMeasurement);
    }

    private void handleSseMessage(final MqttMessage message, final Config.SensorConfig sensorConfig) {
        final JsonParser parser = new JsonParser();
        final JsonObject root = parser.parse(new String(message.getPayload())).getAsJsonObject();

        final JsonObject properties = root.getAsJsonObject("properties");
        final String value = properties.get(sensorConfig.propertyName).getAsString();

        postSensorValue(sensorConfig.sensorName, value, sensorConfig.unitOfMeasurement);
    }

    private void postSensorValue(final String sensorName, final String value, final String unitOfMeasurement) {
        try {
            final int httpCode = sensorValueService.postSensorValue(sensorName, value, unitOfMeasurement);

            if(httpCode != HTTP_OK) {
                LOG.error("post sensor value returned {}", httpCode);
            }
        } catch(final Exception e) {
            LOG.error("exception from post", e);
        }
    }

    private static MqttConnectOptions setUpConnectionOptions() {
        final MqttConnectOptions connOpts = new MqttConnectOptions();
        connOpts.setCleanSession(true);
        connOpts.setUserName(USERNAME);
        connOpts.setPassword(PASSWORD.toCharArray());
        return connOpts;
    }
}
