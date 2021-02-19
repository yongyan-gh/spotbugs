package edu.umd.cs.findbugs.sarif;

import edu.umd.cs.findbugs.*;
import edu.umd.cs.findbugs.annotations.NonNull;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.stream.JsonWriter;

import java.io.IOException;
import java.net.URI;
import java.util.*;
import java.util.stream.Collectors;

public class SarifBugReporter extends BugCollectionBugReporter {
    public SarifBugReporter(Project project) {
        super(project);
    }

    @Override
    public void finish() {
        try {
            JsonWriter jsonWriter = new JsonWriter(outputStream);
            jsonWriter.beginObject();
            jsonWriter.name("version").value("2.1.0").name("$schema").value(
                    "https://raw.githubusercontent.com/oasis-tcs/sarif-spec/master/Schemata/sarif-schema-2.1.0.json");
            processRuns(jsonWriter);
            jsonWriter.endObject();
            getBugCollection().bugsPopulated();
        } catch (IOException e) {
            e.printStackTrace(System.err);
        } finally {
            outputStream.close();
        }
    }

    private void processRuns(@NonNull JsonWriter jsonWriter) throws IOException {
        try {
            Gson gson = new Gson();
            jsonWriter.name("runs").beginArray().beginObject();
            BugCollectionAnalyser analyser = new BugCollectionAnalyser(getBugCollection());
            processTool(jsonWriter, analyser.getRules());
            processInvocations(jsonWriter, analyser.getBaseToId());

            jsonWriter.name("results").beginArray();
            analyser.getResults().forEach((result) -> gson.toJson(result, jsonWriter));
            jsonWriter.endArray();

            jsonWriter.name("originalUriBaseIds");
            gson.toJson(analyser.getOriginalUriBaseIds(), jsonWriter);

            jsonWriter.endObject().endArray(); // end "runs", end "runs" array
        } catch (IOException e) {
            throw e;
        }
    }

    private void processInvocations(JsonWriter jsonWriter, @NonNull Map<URI, String> baseToId) throws IOException {
        List<Notification> configNotifications = new ArrayList<>();

        Set<String> missingClasses = getMissingClasses();
        if (missingClasses == null) {
            missingClasses = Collections.emptySet();
        }
        if (!missingClasses.isEmpty()) {
            String message = String.format("Classes needed for analysis were missing: %s", missingClasses.toString());
            configNotifications.add(new Notification("spotbugs-missing-classes", message, Level.ERROR, null));
        }

        List<Notification> execNotifications = getQueuedErrors().stream()
                .map(t -> Notification.fromError(t, getProject().getSourceFinder(), baseToId))
                .collect(Collectors.toList());

        int exitCode = ExitCodes.from(getQueuedErrors().size(), missingClasses.size(), getBugCollection().getCollection().size());
        Invocation invocation = new Invocation(exitCode,
                getSignalName(exitCode), exitCode == 0,
                execNotifications, configNotifications);
        try {
            Gson gson = new Gson();
            jsonWriter.name("invocations").beginArray();
            gson.toJson(invocation.toJsonObject(), jsonWriter);
            jsonWriter.endArray();
        } catch (IOException e) {
            throw e;
        }
    }

    private void processTool(@NonNull JsonWriter jsonWriter, @NonNull JsonArray rules) throws IOException {
        try {
            Gson gson = new Gson();
            jsonWriter.name("tool").beginObject();
            processExtensions(jsonWriter);

            jsonWriter.name("driver").beginObject();
            jsonWriter.name("name").value("SpotBugs");
            // Eclipse plugin does not follow the semantic-versioning, so use "version" instead of "semanticVersion".
            jsonWriter.name("version").value(Version.VERSION_STRING);
            // SpotBugs refers JVM config to decide which language we use.
            jsonWriter.name("language").value(Locale.getDefault().getLanguage());

            jsonWriter.name("rules").beginArray();
            rules.forEach((rule) -> gson.toJson(rule, jsonWriter));
            jsonWriter.endArray();

            jsonWriter.endObject().endObject(); // end "driver", end "tool"
        } catch (IOException e) {
            throw e;
        }
    }

    private void processExtensions(@NonNull JsonWriter jsonWriter) throws IOException {
        try {
            Gson gson = new Gson();
            jsonWriter.name("extensions").beginArray();
            DetectorFactoryCollection.instance().plugins().stream().map(Extension::fromPlugin).map(Extension::toJsonObject).forEach((
                    jsonObject) -> gson.toJson(jsonObject, jsonWriter));
            jsonWriter.endArray();
        } catch (IOException e) {
            throw e;
        }
    }

    private static String getSignalName(int exitCode) {
        if (exitCode == 0) {
            return "SUCCESS";
        }

        List<String> list = new ArrayList<>();
        if ((exitCode | ExitCodes.ERROR_FLAG) > 0) {
            list.add("ERROR");
        }
        if ((exitCode | ExitCodes.MISSING_CLASS_FLAG) > 0) {
            list.add("MISSING CLASS");
        }
        if ((exitCode | ExitCodes.BUGS_FOUND_FLAG) > 0) {
            list.add("BUGS FOUND");
        }

        return list.isEmpty() ? "UNKNOWN" : list.stream().collect(Collectors.joining(","));
    }
}
