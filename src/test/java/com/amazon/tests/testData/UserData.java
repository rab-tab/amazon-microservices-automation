package com.amazon.tests.testData;

import com.amazon.tests.models.TestModels;

public class UserData {
    private int count = 1;

    public UserData count(int count) {

        this.count = count;
        return this;
    }

    public int getCount() {
        return count;
    }

    public TestModels.UserResponse create() {

        // we'll implement next

        return null;
    }
}
