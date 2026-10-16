package com.k1af.rttyaf.database;

public interface AfterInsertQSLData {
    void doAfterInsert(boolean isInvalid,boolean isNewQSL);
}
