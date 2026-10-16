package com.k1af.rttyaf.database;
/**
 * Callback for querying followed callsigns
 * @author BGY70Z
 * @date 2023-03-20
 */

import java.util.ArrayList;

public interface OnAfterQueryFollowCallsigns {
    void doOnAfterQueryFollowCallsigns(ArrayList<String> callsigns);
}
