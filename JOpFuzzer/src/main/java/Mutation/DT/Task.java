package Mutation.DT;

import java.io.IOException;
import java.util.concurrent.Callable;

import static Utils.Execute.execute;

public class Task implements Callable<Integer> {
    String cmd;

    public Task(String cmd) {
        this.cmd = cmd;
    }

    @Override
    public Integer call() {
        int exitValue;
        try {
            exitValue = execute(cmd);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        return exitValue;
    }
}
