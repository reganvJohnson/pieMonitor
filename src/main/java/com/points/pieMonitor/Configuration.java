package com.points.pieMonitor;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;

public class Configuration {
    public static final String ALERT = "alert";
    public static final String CRITERIA = "criteria";
    public static final String DEFAULT_CRITERIA = "default";
    public static final String DETAILS = "details";
    public static final String QUERY = "query";

    private static Map<String, Object> theConfig;

    public String get(String key) {
        return (String) theConfig.get(key);
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> getObj(String key) {
        return (Map<String, Object>) theConfig.get(key);
    }

    public void setMap(Map<String, Object> theMap) {
        theConfig = theMap;
    }

    @SuppressWarnings("unchecked")
    public String getQuery() {
        String result = "";
        for (String queryLine : (ArrayList<String>) theConfig.get(QUERY)) {
            result += queryLine + "\n";
        }
        return result;
    }

    public String getOneCriteria(String key) {
        @SuppressWarnings("unchecked")
        HashMap<String, String> allCriteria = (HashMap<String, String>) theConfig.get(CRITERIA);
        String oneCriteria = allCriteria.get(key);
        if (oneCriteria == null) {
            oneCriteria = allCriteria.get(DEFAULT_CRITERIA);
        }
        return oneCriteria;
    }
}