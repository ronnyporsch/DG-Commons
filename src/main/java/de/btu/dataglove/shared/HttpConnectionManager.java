package de.btu.dataglove.shared;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import okhttp3.*;

import java.io.IOException;
import java.lang.reflect.Type;
import java.util.*;
import java.util.concurrent.TimeUnit;

/*
this class is used for communicating with the http server
 */
public class HttpConnectionManager {

    private static final OkHttpClient client = new OkHttpClient();
    private static String AUTHORIZATION_HEADER;
    private static String SERVER_URL;
    private static String NAME_OF_FRAME_DB;
    private static String NAME_OF_USER_DB;
    private static String NAME_OF_GESTURE_DB;
    private static String NAME_OF_EULER_GESTURE_DB;
    private static String NAME_OF_TASK_CALCULATOR;
    private static int LIMIT_FOR_HTTP_REQUEST_SIZE;

    /*
    this method needs to be called once by the application before communicating with the server
    this way, the server url and authorization header do not need to be part of the public library of this project
     */
    public static void init(String authorizationHeader, String serverUrl, String nameOfFrameDB, String nameOfUserDB,
                            String nameOfGestureDB, String nameOfEulerGestureDB, String nameOfTaskCalculator, int limitForHttpRequestSize) {
        AUTHORIZATION_HEADER = authorizationHeader;
        SERVER_URL = serverUrl;
        NAME_OF_FRAME_DB = nameOfFrameDB;
        NAME_OF_USER_DB = nameOfUserDB;
        NAME_OF_GESTURE_DB = nameOfGestureDB;
        NAME_OF_EULER_GESTURE_DB = nameOfEulerGestureDB;
        NAME_OF_TASK_CALCULATOR = nameOfTaskCalculator;
        LIMIT_FOR_HTTP_REQUEST_SIZE = limitForHttpRequestSize;
    }

    /*
    saves an object to the database.
    the object can also be a list containing supported elements
    returns true on success, returns false otherwise
     */
    public static boolean saveObjectToDatabase(Object object) throws ClassNotSupportedByDBException, IOException {
        String dbTable;
        if (object instanceof List) {
            dbTable = determineDatabaseTable(((List<?>) object).get(0).getClass());
        } else {
            dbTable = determineDatabaseTable(object.getClass());
        }

        String json = new Gson().toJson(object);
        String jsonWithAddedBoilerplate = addBoilerplateToJson(json);
        RequestBody body = RequestBody.create(jsonWithAddedBoilerplate, MediaType.parse("application/json"));
        Request request = new Request.Builder()
                .url(SERVER_URL + dbTable)// + "?values=" + json)
                .addHeader("Authorization", AUTHORIZATION_HEADER)
                .post(body)
                .build();

        okhttp3.Call call = client.newCall(request);
        Response response = call.execute();
        System.out.println("http response code: " + response.code());
        if (response.code() == 200 || response.code() == 201) {
            Objects.requireNonNull(response.body()).close();
            return true;
        }
        Objects.requireNonNull(response.body()).close();
        return false;
    }

    /*
    retrieves a list of objects from the database
    @param clazz the class of the objects that are to be retrieved
    @param identifiers used to identify the objects in the database
     */
    public static <T> List<T> getObjectsFromDatabase(Class<T> clazz, Map<String, ?> identifiers) throws ClassNotSupportedByDBException, IOException {
        int offset = 0;
        boolean allObjectsReceived = false;
        List<T> objects = new LinkedList<>();
        //multiple get requests can be necessary to get all relevant objects because the server specifies a limit of objects sent per request
        while (!allObjectsReceived) {
            objects.addAll(sendGetRequestToDatabase(clazz, identifiers, offset));
            offset += LIMIT_FOR_HTTP_REQUEST_SIZE;
            if (objects.size() < offset) {
                allObjectsReceived = true;
            }
        }
        return objects;
    }

    private static <T> List<T> sendGetRequestToDatabase(Class<T> clazz, Map<String, ?> identifiers, int offset) throws ClassNotSupportedByDBException, IOException {
        String dbTable = determineDatabaseTable(clazz);

        StringBuilder url = new StringBuilder(SERVER_URL + dbTable);
        if (identifiers != null && !identifiers.isEmpty()) {
            url.append("?q={");
            List<String> identifiersInUrl = new LinkedList<>();
            for (String key : identifiers.keySet()) {
                identifiersInUrl.add(surroundWithBoilerplate(key, identifiers.get(key)));
            }
            url.append(String.join(",", identifiersInUrl))
                    .append("}" + "&start=").append(offset).append("&limit=").append(LIMIT_FOR_HTTP_REQUEST_SIZE);
        }

        Request request = new Request.Builder()
                .url(url.toString())
                .addHeader("Authorization", AUTHORIZATION_HEADER)
                .build();
        Call call = client.newCall(request);

        Response response = call.execute();
        String responseBodyString = Objects.requireNonNull(response.body()).string();
        Gson gson = new Gson();
        JsonObject jsonObject = gson.fromJson(responseBodyString, JsonObject.class);
        JsonArray jsonArray = jsonObject.getAsJsonArray("result");
        List<T> resultList = new LinkedList<>();
        for (int i = 0; i < jsonArray.size(); i++) {
            resultList.add(gson.fromJson(jsonArray.get(i), (Type) clazz));
        }
        Objects.requireNonNull(response.body()).close();
        return resultList;
    }

    /*
    retrieves an aggregate from the database
    @param clazz the class of the objects that are to be retrieved
    @param aggregateFunction the aggregate function to be retrieved (like count or max)
    @param argumentForAggregateFunction the argument for the aggregate function (leaving this empty will send * as the argument)
    @param identifiers used to identify the objects in the database
     */
    public static int getAggregateFromDatabase(Class<?> clazz, String aggregateFunction, String argumentForAggregateFunction,
                                               Map<String, ?> identifiers) throws ClassNotSupportedByDBException, IOException {

        JsonArray jsonArray = sendAggregateRequest(clazz, aggregateFunction, argumentForAggregateFunction, identifiers).getAsJsonArray();
        return jsonArray.get(0).getAsJsonObject().get(aggregateFunction).getAsInt();
    }

    /*
    retrieves all names from frames or gestures from the database
    @param clazz this determines which database table is to be queried
     */
    public static List<String> getAllNamesFromDatabase(Class<?> clazz) throws IOException, ClassNotSupportedByDBException {
        String nameOfColumn;
        if (clazz.getTypeName().equals(DBFrame.class.getTypeName())) {
            nameOfColumn = "nameOfTask";
        } else nameOfColumn = "name";

        JsonArray jsonArray = sendAggregateRequest(clazz, "distinct",
                nameOfColumn, null).getAsJsonArray();
        List<String> resultList = new LinkedList<>();
        for (int i = 0; i < jsonArray.size(); i++) {
            JsonElement entry = jsonArray.get(i).getAsJsonObject().get(nameOfColumn);
            if (!entry.isJsonNull()) {
                resultList.add(entry.getAsString());
            }
        }
        return resultList;
    }

    /*
    retrieves the number of recordings of a given task from the database (column numberOfRecordings in the frameDB)
    @param nameOfTask the name of the task
     */
    public static int getNumberOfRecordings(String nameOfTask) throws IOException {
        Map<String, String> identifiers = new HashMap<>();
        identifiers.put("nameOfTask", nameOfTask);

        JsonArray jsonArray = null;
        try {
            jsonArray = sendAggregateRequest(DBFrame.class, "max", "recordingNumber", identifiers).getAsJsonArray();
            JsonElement result = jsonArray.get(0).getAsJsonObject().get("max");
            if (result.isJsonNull()) return 0;
            else return result.getAsInt();
        } catch (ClassNotSupportedByDBException e) {
            e.printStackTrace();
            System.exit(-1);
            return Integer.MAX_VALUE;
        }
    }

    /*
    sends a get request for an aggregate
    @param clazz the class of the objects that are to be retrieved
    @param aggregateFunction the aggregate function to be retrieved (like count or max)
    @param argumentForAggregateFunction the argument for the aggregate function (leaving this empty will send * as the argument)
    @param identifiers used to identify the objects in the database
     *///TODO currently not respecting the limit of 1000 entries per request
    private static JsonElement sendAggregateRequest(Class<?> clazz, String aggregateFunction, String argumentForAggregateFunction,
                                                    Map<String, ?> identifiers) throws ClassNotSupportedByDBException, IOException {

        String dbTable = determineDatabaseTable(clazz);
        String argumentForAggregateFunctionInURL;
        if (argumentForAggregateFunction == null || argumentForAggregateFunction.equals("")) {
            argumentForAggregateFunctionInURL = "*";
        } else argumentForAggregateFunctionInURL = argumentForAggregateFunction;

        StringBuilder url = new StringBuilder(SERVER_URL + dbTable);
        url.append("?q={\"$").append(aggregateFunction).append("\":")
                .append("\"").append(argumentForAggregateFunctionInURL).append("\"");
        if (identifiers != null && !identifiers.isEmpty()) {
            url.append(", ");
            List<String> identifiersInUrl = new LinkedList<>();
            for (String key : identifiers.keySet()) {
                identifiersInUrl.add(surroundWithBoilerplate(key, identifiers.get(key)));
            }

            url.append(String.join(",", identifiersInUrl));

        }
        url.append("}");

        Request request = new Request.Builder()
                .url(url.toString())
                .addHeader("Authorization", AUTHORIZATION_HEADER)
                .build();
        Call call = client.newCall(request);

        Response response = call.execute();
        String responseBodyString = Objects.requireNonNull(response.body()).string();
        Gson gson = new Gson();
        JsonObject jsonObject = gson.fromJson(responseBodyString, JsonObject.class);
        return jsonObject.get("result");


//        return jsonObject.getAsJsonArray("result")
//                .get(0).getAsJsonObject()
//                .get(aggregateFunction).getAsInt();
    }

    /*
    convenience method that retrieves a list of frames from the database based on their nameOfTask
     */
    public static List<Frame> getFramesFromDatabase(String nameOfTask) throws IOException {
        Map<String, String> identifiers = new HashMap<>();
        identifiers.put("nameOfTask", nameOfTask);
        List<DBFrame> dbFrames = null;
        try {
            dbFrames = getObjectsFromDatabase(DBFrame.class, identifiers);
        } catch (ClassNotSupportedByDBException e) {
            e.printStackTrace();
        }
        List<Frame> frames = new LinkedList<>();
        for (DBFrame dbFrame : Objects.requireNonNull(dbFrames)) {
            frames.add(dbFrame.convertToFrame());
        }
        return frames;
    }

    /*
    convenience method that saves a list of frames to the database
     */
    public static boolean saveFramesToDatabase(List<Frame> frames) throws IOException {
        List<DBFrame> dbFrames = new LinkedList<>();
        for (Frame frame : frames) {
            dbFrames.add(new DBFrame((frame)));
        }
        try {
            return saveObjectToDatabase(dbFrames);
        } catch (ClassNotSupportedByDBException e) {
            e.printStackTrace();
            return false;
        }
    }

    /*
    convenience method that retrieves a list of gestures from the database based on their name
     */
    public static List<Gesture> getGesturesFromDatabase(String name) throws IOException {
        Map<String, String> map = new HashMap<>();
        map.put("name", name);
        List<Gesture> gestures = new LinkedList<>();
        try {
            gestures = getObjectsFromDatabase(Gesture.class, map);
        } catch (ClassNotSupportedByDBException e) {
            e.printStackTrace();
        }
        return gestures;
    }

    /*
    convenience method that retrieves a list of eulerGestures from the database based on their name
    */
    public static List<EulerGesture> getEulerGesturesFromDatabase(String name) throws IOException {
        Map<String, String> map = new HashMap<>();
        map.put("name", name);
        List<EulerGesture> gestures = new LinkedList<>();
        try {
            gestures = getObjectsFromDatabase(EulerGesture.class, map);
        } catch (ClassNotSupportedByDBException e) {
            e.printStackTrace();
        }
        return gestures;
    }

    /*
    requests a calculation from the server with the result then being saved to the database.
    returns the exit code of the task calculator program
    @param encodedArgument the encoded metadata of the task that is to be calculated
    @param versionOfProgram the version of taskCalculator that is queried. example: 1.0.0
    */
    public static int requestCalculationFromServer(String encodedArgument, String versionOfProgram) throws IOException {
        String url = SERVER_URL + NAME_OF_TASK_CALCULATOR +
                "?argv=[\"" +
                encodedArgument +
                "\"]&jar=TaskCalculator-" + versionOfProgram + ".jar";
        Request request = new Request.Builder()
                .url(url)
                .addHeader("Authorization", AUTHORIZATION_HEADER)
                .build();
        OkHttpClient extendedTimeoutClient = client.newBuilder()
                .readTimeout(0, TimeUnit.MILLISECONDS)
                .build();
        Call call = extendedTimeoutClient.newCall(request);

        Response response = call.execute();
        String responseBodyString = Objects.requireNonNull(response.body()).string();
        Gson gson = new Gson();
        JsonObject jsonObject = gson.fromJson(responseBodyString, JsonObject.class);
        int exitCode = jsonObject.get("exitcode").getAsInt();
        Objects.requireNonNull(response.body()).close();
        return exitCode;
    }

    /*
    each class has their own equivalent table in the database. This returns the table for a given class
     */
    private static String determineDatabaseTable(Class<?> clazz) throws ClassNotSupportedByDBException {
        if (clazz.getTypeName().equals(Gesture.class.getTypeName())) {
            return NAME_OF_GESTURE_DB;
        }
        if (clazz.getTypeName().equals(EulerGesture.class.getTypeName())) {
            return NAME_OF_EULER_GESTURE_DB;
        }
        if (clazz.getTypeName().equals(DBFrame.class.getTypeName())) {
            return NAME_OF_FRAME_DB;
        }
        throw new ClassNotSupportedByDBException(clazz);
    }

    /*
    surrounds an attribute/value pair with additional chars that are needed for it to be used in the http request
     */
    private static String surroundWithBoilerplate(String attribute, Object value) {
        String firstPart = "\"\\\"" + attribute + "\\\"\" : ";
        String secondPart;
        if (value instanceof String) {
            secondPart = "\"'" + value + "'\"";
        } else {
            secondPart = value.toString();
        }
        return firstPart + secondPart;
    }

    /*
    adds some boilerplate that is needed by the http server when sending data in the http request body
    */
    private static String addBoilerplateToJson(String json) {
        return "{\"values\":" + json + "}";
    }

    public static class ClassNotSupportedByDBException extends Exception {
        private ClassNotSupportedByDBException(Class<?> clazz) {
            super("Class not supported by db: " + clazz);
        }
    }
}
