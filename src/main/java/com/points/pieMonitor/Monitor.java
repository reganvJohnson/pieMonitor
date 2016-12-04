package com.points.pieMonitor;

import java.io.BufferedReader;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;

import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.script.Invocable;
import javax.script.ScriptEngine;
import javax.script.ScriptEngineManager;
import javax.script.ScriptException;

import com.fasterxml.jackson.core.JsonParseException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import com.splunk.HttpService;
import com.splunk.Job;
import com.splunk.JobArgs;
import com.splunk.ResultsReaderXml;
import com.splunk.Service;
import com.splunk.ServiceArgs;

import com.splunk.SSLSecurityProtocol;

public class Monitor {
    private Configuration theConfig = new Configuration();
    private static String CONFIG_FILE = "monitor.json";

    public static void main(String[] args) {

        try {
            new Monitor().doMonitoring();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void doMonitoring() throws Exception {
        readConfig();
        //query splunk, and get all the results.
        List<Map<String, String>> allEvents = runQuery();
        // pick out the results that match our criteria
        Map<String, Object> details = evaluateEvents(allEvents);

        if (details.size() > 0) {
            // send alert to pagerDuty
            sendAlert(details);
        }
    }

    boolean sendAlert(Map<String, Object> details) throws IOException {

        Map<String, Object> alertMessage = (Map<String, Object>) theConfig.getObj(Configuration.ALERT);
        alertMessage.put(Configuration.DETAILS, details);
        ObjectMapper mapper = new ObjectMapper();
        String alertBody = mapper.writerWithDefaultPrettyPrinter().writeValueAsString(alertMessage);
        System.out.println(alertBody);

        URL object = new URL("https://events.pagerduty.com/generic/2010-04-15/create_event.json");

        HttpURLConnection con = (HttpURLConnection) object.openConnection();
        con.setDoOutput(true);
        con.setDoInput(true);
        con.setRequestProperty("Content-Type", "text/json");
        con.setRequestProperty("Accept", "text/json");
        con.setRequestMethod("POST");

        OutputStreamWriter wr = new OutputStreamWriter(con.getOutputStream());
        wr.write(alertBody);
        wr.flush();

        StringBuilder sb = new StringBuilder();
        int HttpResult = con.getResponseCode();
        if (HttpResult == HttpURLConnection.HTTP_OK) {
            BufferedReader br = new BufferedReader(new InputStreamReader(con.getInputStream(), "utf-8"));
            String line = null;
            while ((line = br.readLine()) != null) {
                sb.append(line + "\n");
            }
            br.close();
            System.out.println("1" + sb.toString());
        } else {
            System.out.println(HttpResult);
            System.out.println("2" + con.getResponseMessage());
        }
        return HttpResult == HttpURLConnection.HTTP_OK;
    }

    private void readConfig() throws JsonParseException, JsonMappingException, IOException {

        ObjectMapper mapper = new ObjectMapper();
        // read JSON from a file
        Map<String, Object> map = mapper.readValue(new File(CONFIG_FILE), new TypeReference<Map<String, Object>>() {
        });
        theConfig.setMap(map);
    }

    // compare the configuration to the list of events, and report on the results
    private Map<String, Object> evaluateEvents(List<Map<String, String>> allEvents)
            throws ScriptException, NoSuchMethodException {

        Map<String, Object> details = new HashMap<String, Object>();

        for (Map<String, String> event : allEvents) {
            String domain = event.get("realdomain");
            String oneCriteria = theConfig.getOneCriteria(domain);
            if (oneCriteria != null) {
                String functions = "var fun1 = function(result, oneCriteria) {\n" + "return eval(oneCriteria);\n" + "}";

                ScriptEngine engine = new ScriptEngineManager().getEngineByName("nashorn");
                engine.eval(functions);
                Invocable invocable = (Invocable) engine;
                Boolean result = (Boolean) invocable.invokeFunction("fun1", event, oneCriteria);
                if (true == result.booleanValue()) {
                    HashMap<String, Object> oneEvent = new HashMap<String, Object>();
                    oneEvent.put("query", event);
                    oneEvent.put("criteria", oneCriteria);
                    details.put(domain, oneEvent);
                }
            }
        }
        return details;
    }

    private Service login() {
        HttpService.setSslSecurityProtocol(SSLSecurityProtocol.TLSv1_2);
        // Create a map of arguments and add login parameters
        ServiceArgs loginArgs = new ServiceArgs();
        loginArgs.setUsername(theConfig.get("username"));
        loginArgs.setPassword(theConfig.get("password"));
        loginArgs.setHost(theConfig.get("host"));
        loginArgs.setPort(Integer.parseInt(theConfig.get("port")));

        // Create a Service instance and log in with the argument map
        Service service = Service.connect(loginArgs);
        return service;
    }

    private List<Map<String, String>> runQuery() {
        Service service = login();
        Job job = runAsynchronousQuery(service);
        List<Map<String, String>> allEvents = retrieveEvents(job);
        reportJobResults(job);
        return allEvents;
    }

    Job runAsynchronousQuery(Service service) {
        String searchQuery_normal = theConfig.getQuery();
        System.out.println(searchQuery_normal);
        JobArgs jobargs = new JobArgs();
        jobargs.setExecutionMode(JobArgs.ExecutionMode.NORMAL);
        jobargs.add("earliest_time", theConfig.get("earliest_time"));
        jobargs.add("latest_time", theConfig.get("latest_time"));
        Job job = service.getJobs().create(searchQuery_normal, jobargs);

        // Wait for the search to finish
        while (!job.isDone()) {
            try {
                Thread.sleep(500);
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        }
        return job;
    }    

    private void reportJobResults(Job job) {
        // report on properties of the completed job
        System.out.println("\nSearch job properties\n---------------------");
        System.out.println("Search job ID:         " + job.getSid());
        System.out.println("The number of events:  " + job.getEventCount());
        System.out.println("The number of results: " + job.getResultCount());
        System.out.println("Search duration:       " + job.getRunDuration() + " seconds");
        System.out.println("This job expires in:   " + job.getTtl() + " seconds");
    }

    private ArrayList<Map<String, String>> retrieveEvents(Job job) {
        // Get the Splunk query results 
        InputStream resultsNormalSearch = job.getResults();

        ResultsReaderXml resultsReaderNormalSearch;
        ArrayList<Map<String, String>> allEvents = new ArrayList<Map<String, String>>();
        try {
            resultsReaderNormalSearch = new ResultsReaderXml(resultsNormalSearch);
            HashMap<String, String> event;
            while ((event = resultsReaderNormalSearch.getNextEvent()) != null) {
                allEvents.add(event);
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        return allEvents;
    }
}
