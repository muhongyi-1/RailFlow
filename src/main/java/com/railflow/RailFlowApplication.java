package com.railflow;

import com.railflow.db.DatabaseInitializer;
import com.railflow.maintenance.MaintenanceCommands;
import org.springframework.boot.*;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class RailFlowApplication {
    public static void main(String[] args) {
        var arguments = new DefaultApplicationArguments(args);
        String command = arguments.containsOption("railflow.command")
                ? arguments.getOptionValues("railflow.command").stream().findFirst().orElse("help") : "api";
        var application = new SpringApplication(RailFlowApplication.class);
        if (!command.equals("api")) application.setWebApplicationType(WebApplicationType.NONE);
        // CLI service modes override the file's disabled defaults without requiring extra flags.
        if (command.equals("payment-consumer")) args = append(args, "--railflow.worker.enabled=true");
        if (command.equals("order-scheduler")) args = append(args, "--railflow.scheduler.enabled=true");
        ConfigurableApplicationContext context = application.run(args);
        if (command.equals("api") || command.equals("payment-consumer") || command.equals("order-scheduler")) return;
        int exitCode = 0;
        try (context) {
            switch (command) {
                case "create-tables" -> context.getBean(DatabaseInitializer.class).createTables();
                case "init-data" -> context.getBean(DatabaseInitializer.class).seed();
                case "help" -> System.out.println("Commands: api, create-tables, init-data, payment-consumer, order-scheduler, inventory-audit, inventory-repair, dead-letter-audit, payment-close-race, booking-load-test, simulate-payment-callback. See README.md.");
                default -> context.getBean(MaintenanceCommands.class).run(command, arguments);
            }
        } catch (MaintenanceCommands.CommandFailure failure) {
            System.err.println(failure.getMessage());
            exitCode = failure.getExitCode();
        } catch (IllegalArgumentException failure) {
            System.err.println(failure.getMessage());
            exitCode = 2;
        }
        if (exitCode != 0) System.exit(exitCode);
    }

    private static String[] append(String[] args, String value) {
        String[] result = java.util.Arrays.copyOf(args, args.length + 1);
        result[args.length] = value;
        return result;
    }
}
