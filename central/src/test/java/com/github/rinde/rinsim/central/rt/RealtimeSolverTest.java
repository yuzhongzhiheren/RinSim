/*
 * Copyright (C) 2011-2015 Rinde van Lon, iMinds-DistriNet, KU Leuven
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *         http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.github.rinde.rinsim.central.rt;

import static com.google.common.base.Preconditions.checkState;
import static com.google.common.truth.Truth.assertThat;
import static java.util.Arrays.asList;

import java.util.ArrayList;
import java.util.List;

import org.junit.BeforeClass;
import org.junit.Test;

import com.github.rinde.rinsim.central.GlobalStateObject;
import com.github.rinde.rinsim.central.RandomSolver;
import com.github.rinde.rinsim.central.Solver;
import com.github.rinde.rinsim.central.rt.RtCentral.AdapterSupplier;
import com.github.rinde.rinsim.core.Simulator;
import com.github.rinde.rinsim.core.SimulatorAPI;
import com.github.rinde.rinsim.core.model.DependencyProvider;
import com.github.rinde.rinsim.core.model.Model.AbstractModelVoid;
import com.github.rinde.rinsim.core.model.ModelBuilder.AbstractModelBuilder;
import com.github.rinde.rinsim.core.model.pdp.DefaultPDPModel;
import com.github.rinde.rinsim.core.model.pdp.PDPModel;
import com.github.rinde.rinsim.core.model.pdp.PDPModel.PDPModelEventType;
import com.github.rinde.rinsim.core.model.pdp.PDPModelEvent;
import com.github.rinde.rinsim.core.model.pdp.Parcel;
import com.github.rinde.rinsim.core.model.pdp.Vehicle;
import com.github.rinde.rinsim.core.model.pdp.VehicleDTO;
import com.github.rinde.rinsim.core.model.road.RoadModel;
import com.github.rinde.rinsim.core.model.road.RoadModelBuilders;
import com.github.rinde.rinsim.core.model.time.RealTimeClockController;
import com.github.rinde.rinsim.core.model.time.RealTimeClockController.ClockMode;
import com.github.rinde.rinsim.core.model.time.TickListener;
import com.github.rinde.rinsim.core.model.time.TimeLapse;
import com.github.rinde.rinsim.core.model.time.TimeModel;
import com.github.rinde.rinsim.event.Event;
import com.github.rinde.rinsim.event.Listener;
import com.github.rinde.rinsim.experiment.MASConfiguration;
import com.github.rinde.rinsim.geom.Point;
import com.github.rinde.rinsim.pdptw.common.AddDepotEvent;
import com.github.rinde.rinsim.pdptw.common.AddParcelEvent;
import com.github.rinde.rinsim.pdptw.common.AddVehicleEvent;
import com.github.rinde.rinsim.pdptw.common.PDPRoadModel;
import com.github.rinde.rinsim.pdptw.common.RouteFollowingVehicle;
import com.github.rinde.rinsim.pdptw.common.StatsStopConditions;
import com.github.rinde.rinsim.pdptw.common.StatsTracker;
import com.github.rinde.rinsim.scenario.Scenario;
import com.github.rinde.rinsim.scenario.ScenarioController;
import com.github.rinde.rinsim.scenario.TimeOutEvent;
import com.github.rinde.rinsim.scenario.TimedEvent;
import com.github.rinde.rinsim.scenario.TimedEventHandler;
import com.github.rinde.rinsim.testutil.TestUtil;
import com.github.rinde.rinsim.util.StochasticSupplier;
import com.github.rinde.rinsim.util.StochasticSuppliers;
import com.github.rinde.rinsim.util.TimeWindow;
import com.google.auto.value.AutoValue;
import com.google.common.base.Optional;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Iterators;
import com.google.common.collect.PeekingIterator;
import com.google.common.collect.Range;

/**
 * @author Rinde van Lon
 *
 */
public class RealtimeSolverTest {
  // .addModel(
  // View.builder()
  // .withAutoPlay()
  // .with(PDPModelRenderer.builder()
  // .withDestinationLines())
  // .with(RouteRenderer.builder())
  // .with(RoadUserRenderer.builder())
  // .with(PlaneRoadModelRenderer.builder())
  // .with(TimeLinePanel.builder()))

  // TODO add test for VehicleCheckerModel

  // TODO add tests for decentralized use case of SolverModel

  /**
   * Tests some 'unreachable' code.
   */
  @BeforeClass
  public static void setUpClass() {
    TestUtil.testEnum(RtCentral.VehicleCreator.class);
    TestUtil.testPrivateConstructor(RtCentral.class);
  }

  static Simulator.Builder init(
    TimedEventHandler<AddVehicleEvent> vehicleHandler,
    Iterable<? extends TimedEvent> events) {

    Scenario scenario = Scenario.builder()
      .addEvent(AddDepotEvent.create(-1, new Point(5, 5)))
      .addEvent(AddVehicleEvent.create(-1, VehicleDTO.builder().build()))
      .addEvent(AddVehicleEvent.create(-1, VehicleDTO.builder().build()))
      .addEvents(events)
      .build();

    ScenarioController.Builder sb = ScenarioController.builder(scenario)
      .withEventHandler(AddParcelEvent.class, AddParcelEvent.defaultHandler())
      .withEventHandler(AddVehicleEvent.class, vehicleHandler)
      .withEventHandler(AddDepotEvent.class, AddDepotEvent.defaultHandler())
      .withEventHandler(TimeOutEvent.class,
        TimeOutEvent.ignoreHandler())
      .withOrStopCondition(StatsStopConditions.vehiclesDoneAndBackAtDepot())
      .withOrStopCondition(StatsStopConditions.timeOutEvent());

    return Simulator.builder()
      .addModel(PDPRoadModel.builder(RoadModelBuilders.plane())
        .withAllowVehicleDiversion(true))
      .addModel(DefaultPDPModel.builder())
      .addModel(TimeModel.builder()
        .withRealTime()
        .withStartInClockMode(ClockMode.SIMULATED)
        .withTickLength(100))
      .addModel(sb)
      .addModel(StatsTracker.builder());
  }

  /**
   * This test tests whether the clock mode is switched automatically from
   * simulated to real time and back when needed.
   */
  @Test
  public void testDynamicClockModeSwitching() {
    List<TimedEvent> events = asList(AddParcelEvent
      .create(Parcel.builder(new Point(0, 0), new Point(3, 3))
        .orderAnnounceTime(200)
        .pickupTimeWindow(new TimeWindow(1000, 2000))
        .buildDTO()),
      AddParcelEvent
        .create(Parcel.builder(new Point(0, 0), new Point(3, 3))
          .orderAnnounceTime(1000)
          .pickupTimeWindow(new TimeWindow(60000, 80000))
          .serviceDuration(180000L)
          .buildDTO()),
      TimeOutEvent.create(3000));

    Simulator sim = init(RtCentral.vehicleHandler(), events)
      .addModel(
        RtCentral.builder(StochasticSuppliers.constant(new TestRtSolver())))
      .addModel(ClockInspectModel.builder())
      .build();

    sim.start();

    ClockInspectModel cim = sim.getModelProvider()
      .getModel(ClockInspectModel.class);

    cim.assertLog();
  }

  /**
   * Tests that correct vehicles receive routes and that vehicles respond to new
   * events as fast as possible.
   */
  @Test
  public void testCentralRouteAssignment() {
    List<TimedEvent> events = asList(
      AddParcelEvent.create(Parcel.builder(new Point(1, 1), new Point(3, 3))
        .orderAnnounceTime(200)
        .pickupTimeWindow(new TimeWindow(200, 2000))
        .buildDTO()),
      AddParcelEvent.create(Parcel.builder(new Point(1, 4), new Point(3, 4))
        .orderAnnounceTime(60000)
        .pickupTimeWindow(new TimeWindow(60000, 80000))
        .serviceDuration(180000L)
        .buildDTO()),
      TimeOutEvent.create(1800000));

    final List<RouteFollowingVehicle> vehicles = new ArrayList<>();
    Simulator sim = init(new TestVehicleHandler(vehicles), events)
      .addModel(
        RtCentral.builderAdapt(StochasticSuppliers.constant(new Solver() {
          @Override
          public ImmutableList<ImmutableList<Parcel>> solve(
            GlobalStateObject state) {
            return ImmutableList.of(ImmutableList.<Parcel> builder()
              .addAll(state.getAvailableParcels())
              .addAll(state.getAvailableParcels())
              .build(),
              ImmutableList.<Parcel> of());
          }
        })))
      .build();

    final RoadModel rm = sim.getModelProvider().getModel(RoadModel.class);
    sim.register(new TickListener() {

      @Override
      public void tick(TimeLapse timeLapse) {
        if (timeLapse.getStartTime() == 100) {
          assertThat(rm.getPosition(vehicles.get(0)))
            .isEqualTo(new Point(0, 0));
          assertThat(vehicles.get(0).getRoute()).isEmpty();
          assertThat(vehicles.get(1).getRoute()).isEmpty();
        } else if (timeLapse.getStartTime() == 200) {
          // this is the tick in which the new order event is dispatched. This
          // code is executed just before the tick implementation of the
          // vehicle, therefore the vehicle should not have moved yet.
          assertThat(rm.getPosition(vehicles.get(0)))
            .isEqualTo(new Point(0, 0));
          assertThat(vehicles.get(0).getRoute()).hasSize(2);
          assertThat(vehicles.get(1).getRoute()).isEmpty();
        } else if (timeLapse.getStartTime() == 59900) {
          assertThat(vehicles.get(0).getRoute()).hasSize(2);
          assertThat(vehicles.get(1).getRoute()).isEmpty();
        } else if (timeLapse.getStartTime() == 60000) {
          assertThat(vehicles.get(0).getRoute()).hasSize(4);
          assertThat(vehicles.get(1).getRoute()).isEmpty();
        }
      }

      @Override
      public void afterTick(TimeLapse timeLapse) {
        if (timeLapse.getStartTime() == 200) {
          // this is the tick in which the new order event was dispatched. This
          // check is to ensure that the vehicle moves at the earliest possible
          // time (which is in the same tick) towards the new order.
          assertThat(rm.getPosition(vehicles.get(0)))
            .isNotEqualTo(new Point(0, 0));
          assertThat(vehicles.get(0).getRoute()).hasSize(2);
          assertThat(vehicles.get(1).getRoute()).isEmpty();
        }
      }
    });

    sim.start();
  }

  /**
   * Tests that a long computation is successfully cancelled when the problem
   * has changed in the mean time.
   */
  @Test
  public void testCancelLongComputation() {
    // scenario
    // 1. problem changes (state1)
    // 2. solver start computing (state1)
    // 3. problem changes (state2)
    // 4. solver starts computing, and cancels computation of state1 (state2)

    List<TimedEvent> events = asList(
      AddParcelEvent.create(Parcel.builder(new Point(1, 1), new Point(3, 3))
        .orderAnnounceTime(200)
        .pickupTimeWindow(new TimeWindow(200, 2000))
        .buildDTO()),
      AddParcelEvent.create(Parcel.builder(new Point(1, 4), new Point(3, 4))
        .orderAnnounceTime(1000)
        .pickupTimeWindow(new TimeWindow(1000, 80000))
        .serviceDuration(180000L)
        .buildDTO()),
      TimeOutEvent.create(1800000));

    RtSolverCheckerSupplierAdapter r = new RtSolverCheckerSupplierAdapter(
      AdapterSupplier.create(StochasticSuppliers.constant(new Solver() {
        @Override
        public ImmutableList<ImmutableList<Parcel>> solve(
          GlobalStateObject state) {
          try {
            Thread.sleep(1000);
          } catch (InterruptedException e) {
            throw new IllegalStateException(e);
          }
          return ImmutableList.of(ImmutableList.<Parcel> builder()
            .addAll(state.getAvailableParcels())
            .addAll(state.getAvailableParcels())
            .build(),
            ImmutableList.<Parcel> of());
        }
      })));

    Simulator sim = init(RtCentral.vehicleHandler(), events)
      .addModel(
        RtCentral.builder(r))
      .build();

    sim.start();

    RealtimeSolverChecker solverChecker = r.checker.get();
    SchedulerChecker schedulerChecker = solverChecker.schedulerChecker.get();
    assertThat(solverChecker.initCalls).isEqualTo(1);
    assertThat(solverChecker.receiveSnapshotCalls).isEqualTo(2);
    assertThat(schedulerChecker.doneForNowCalls).isEqualTo(1);
    assertThat(schedulerChecker.updateScheduleCalls).isEqualTo(1);
  }

  /**
   * Tests the consistency checker in {@link RtCentral}. The test simulates a
   * scenario where a parcel p0 has been picked up by v0 but the solver assigns
   * it to v1 (due to the delay of the solver). v1 will automatically discard
   * the assignment of p0 because it recognizes that it is no longer available.
   * This leads to a situation where p0 would never be delivered, the
   * consistency check should recognize this situation and ask the solver to
   * solve the problem again (but then in its new form).
   */
  @Test
  public void testConsistencyChecker() {
    List<TimedEvent> events = asList(
      AddParcelEvent.create(Parcel.builder(new Point(1, 1), new Point(9, 9))
        .orderAnnounceTime(200)
        .pickupTimeWindow(new TimeWindow(200, 2000))
        .buildDTO()),
      AddParcelEvent.create(Parcel.builder(new Point(1, 4), new Point(3, 4))
        .orderAnnounceTime(102000)
        .pickupTimeWindow(new TimeWindow(102100, 800000))
        .serviceDuration(180000L)
        .buildDTO()),
      TimeOutEvent.create(1800000));

    final List<GlobalStateObject> snapshots = new ArrayList<>();
    final List<RouteFollowingVehicle> vehicles = new ArrayList<>();
    Simulator sim = init(new TestVehicleHandler(vehicles), events)
      .addModel(
        RtCentral.builderAdapt(StochasticSuppliers.constant(new Solver() {
          @Override
          public ImmutableList<ImmutableList<Parcel>> solve(
            GlobalStateObject state) {
            snapshots.add(state);

            if (snapshots.size() == 1) {
              return ImmutableList.of(ImmutableList.<Parcel> builder()
                .addAll(state.getAvailableParcels())
                .addAll(state.getAvailableParcels())
                .build(),
                ImmutableList.<Parcel> of());
            } else if (snapshots.size() == 2) {
              try {
                Thread.sleep(300);
              } catch (InterruptedException e) {
                throw new IllegalStateException(e);
              }
              return ImmutableList.of(ImmutableList.<Parcel> of(),
                ImmutableList.<Parcel> builder()
                  .addAll(state.getAvailableParcels())
                  .addAll(state.getAvailableParcels())
                  .build());
            } else if (snapshots.size() == 3) {
              return ImmutableList.of(
                ImmutableList.copyOf(state.getVehicles().get(0).getContents()),
                ImmutableList.<Parcel> builder()
                  .addAll(state.getAvailableParcels())
                  .addAll(state.getAvailableParcels())
                  .build());
            } else {
              throw new IllegalStateException();
            }
          }
        })))

    .build();

    final List<PDPModelEvent> pdpEvents = new ArrayList<>();
    sim.getModelProvider().getModel(PDPModel.class).getEventAPI()
      .addListener(new Listener() {
        @Override
        public void handleEvent(Event e) {
          pdpEvents.add((PDPModelEvent) e);
        }
      }, PDPModelEventType.values());

    sim.start();

    assertThat(pdpEvents).hasSize(14);
    List<PDPModelEvent> sub = pdpEvents.subList(10, 14);

    Vehicle v0 = vehicles.get(0);
    Vehicle v1 = vehicles.get(1);
    Parcel p0 = pdpEvents.get(2).parcel;
    Parcel p1 = pdpEvents.get(4).parcel;

    // check that the correct vehicles have delivered the correct parcels
    // v0 should have delivered p0
    // v1 should have delivered p1
    assertThat(sub.get(0).getEventType())
      .isSameAs(PDPModelEventType.START_DELIVERY);
    assertThat(sub.get(0).vehicle).isSameAs(v1);
    assertThat(sub.get(0).parcel).isSameAs(p1);
    assertThat(sub.get(1).getEventType())
      .isSameAs(PDPModelEventType.END_DELIVERY);
    assertThat(sub.get(1).vehicle).isSameAs(v1);
    assertThat(sub.get(1).parcel).isSameAs(p1);

    assertThat(sub.get(2).getEventType())
      .isSameAs(PDPModelEventType.START_DELIVERY);
    assertThat(sub.get(2).vehicle).isSameAs(v0);
    assertThat(sub.get(2).parcel).isSameAs(p0);
    assertThat(sub.get(3).getEventType())
      .isSameAs(PDPModelEventType.END_DELIVERY);
    assertThat(sub.get(3).vehicle).isSameAs(v0);
    assertThat(sub.get(3).parcel).isSameAs(p0);
  }

  /**
   * Checks that the name of the produced {@link MASConfiguration} is
   * meaningful.
   */
  @Test
  public void nameTest() {
    assertThat(RtCentral
      .solverConfigurationAdapt(RandomSolver.supplier(), "hallo").getName())
        .isEqualTo("RtCentral-RtAdapter{RandomSolverSupplier}hallo");

    String name2 = RtCentral
      .solverConfiguration(StochasticSuppliers.constant(new RealtimeSolver() {
        @Override
        public void receiveSnapshot(GlobalStateObject snapshot) {}

        @Override
        public void init(Scheduler scheduler) {}

        @Override
        public String toString() {
          return "THEsupplier";
        }
      }), "hallo").getName();

    assertThat(name2)
      .isEqualTo("RtCentral-StochasticSuppliers.constant(THEsupplier)hallo");
  }

  static class RtSolverCheckerSupplierAdapter
    implements StochasticSupplier<RealtimeSolver> {

    StochasticSupplier<RealtimeSolver> delegate;
    Optional<RealtimeSolverChecker> checker;

    RtSolverCheckerSupplierAdapter(
      StochasticSupplier<RealtimeSolver> del) {
      delegate = del;
      checker = Optional.absent();
    }

    @Override
    public RealtimeSolver get(long seed) {
      checker = Optional.of(new RealtimeSolverChecker(delegate.get(seed)));
      return checker.get();
    }
  }

  static class RealtimeSolverChecker implements RealtimeSolver {
    final RealtimeSolver delegate;

    int initCalls;
    int receiveSnapshotCalls;
    Optional<SchedulerChecker> schedulerChecker;

    RealtimeSolverChecker(RealtimeSolver s) {
      delegate = s;
      schedulerChecker = Optional.absent();
    }

    @Override
    public void init(Scheduler scheduler) {
      initCalls++;
      checkState(!schedulerChecker.isPresent());
      schedulerChecker = Optional.of(new SchedulerChecker(scheduler));
      delegate.init(schedulerChecker.get());
    }

    @Override
    public void receiveSnapshot(GlobalStateObject snapshot) {
      receiveSnapshotCalls++;
      delegate.receiveSnapshot(snapshot);
    }
  }

  static class SchedulerChecker extends Scheduler {
    final Scheduler delegate;
    int updateScheduleCalls;
    int getCurrentScheduleCalls;
    int doneForNowCalls;

    SchedulerChecker(Scheduler s) {
      delegate = s;
    }

    @Override
    public void updateSchedule(ImmutableList<ImmutableList<Parcel>> routes) {
      updateScheduleCalls++;
      delegate.updateSchedule(routes);
    }

    @Override
    public ImmutableList<ImmutableList<Parcel>> getCurrentSchedule() {
      getCurrentScheduleCalls++;
      return delegate.getCurrentSchedule();
    }

    @Override
    public void doneForNow() {
      doneForNowCalls++;
      delegate.doneForNow();
    }
  }

  static class TestVehicleHandler
    implements TimedEventHandler<AddVehicleEvent> {

    final List<RouteFollowingVehicle> vehicles;

    // all created vehicles will be added to this list
    TestVehicleHandler(List<RouteFollowingVehicle> vs) {
      vehicles = vs;
    }

    @Override
    public void handleTimedEvent(AddVehicleEvent event,
      SimulatorAPI simulator) {
      RouteFollowingVehicle v = new RouteFollowingVehicle(event.getVehicleDTO(),
        true);
      simulator.register(v);
      vehicles.add(v);
    }
  }

  static class ClockInspectModel extends AbstractModelVoid
    implements TickListener {
    RealTimeClockController clock;

    List<ClockLogEntry> clockLog;

    ClockInspectModel(RealTimeClockController c) {
      clock = c;
      clockLog = new ArrayList<>();
    }

    @Override
    public void tick(TimeLapse timeLapse) {
      clockLog.add(
        new ClockLogEntry(System.nanoTime(), timeLapse, clock.getClockMode()));
    }

    @Override
    public void afterTick(TimeLapse timeLapse) {}

    void assertLog() {
      // events at 200 and 1000
      assertThat(clockLog).hasSize(31);

      assertClockModeIs(ClockMode.SIMULATED, 0, 2);
      assertClockModeIs(ClockMode.REAL_TIME, 3, 5);
      // 6 can be either, depending on thread scheduling
      assertClockModeIs(ClockMode.SIMULATED, 7, 10);
      assertClockModeIs(ClockMode.REAL_TIME, 11, 13);
      // 14 can be either, depending on thread scheduling
      assertClockModeIs(ClockMode.SIMULATED, 15, 30);

      assertTimeConsistency();
    }

    void assertClockModeIs(ClockMode m, int start, int end) {
      for (int i = start; i <= end; i++) {
        assertThat(clockLog.get(i).mode).named(Integer.toString(i))
          .isEqualTo(m);
      }
    }

    void assertTimeConsistency() {
      final PeekingIterator<ClockLogEntry> it = Iterators
        .peekingIterator(clockLog.iterator());
      final List<Double> interArrivalTimes = new ArrayList<>();
      for (ClockLogEntry l1 = it.next(); it.hasNext(); l1 = it.next()) {
        final ClockLogEntry l2 = it.peek();
        interArrivalTimes.add((l2.wallTime - l1.wallTime) / 1000000d);
      }

      Range<Double> acceptableDuration = Range.closed(90d, 120d);
      assertThat(sum(interArrivalTimes.subList(0, 3))).isAtMost(100d);
      assertThat(interArrivalTimes.get(3)).isIn(acceptableDuration);
      assertThat(interArrivalTimes.get(4)).isIn(acceptableDuration);
      assertThat(sum(interArrivalTimes.subList(6, 11))).isAtMost(100d);
      assertThat(interArrivalTimes.get(11)).isIn(acceptableDuration);
      assertThat(interArrivalTimes.get(12)).isIn(acceptableDuration);
      assertThat(sum(interArrivalTimes.subList(14, 30))).isAtMost(100d);
    }

    double sum(List<Double> list) {
      double sum = 0d;
      for (double l : list) {
        sum += l;
      }
      return sum;
    }

    static Builder builder() {
      return new AutoValue_RealtimeSolverTest_ClockInspectModel_Builder();
    }

    static class ClockLogEntry {
      final long wallTime;
      final Range<Long> timeLapse;
      final ClockMode mode;

      ClockLogEntry(long wt, TimeLapse tl, ClockMode m) {
        wallTime = wt;
        timeLapse = Range.closedOpen(tl.getStartTime(), tl.getEndTime());
        mode = m;
      }
    }

    @AutoValue
    abstract static class Builder
      extends AbstractModelBuilder<ClockInspectModel, Void> {

      Builder() {
        setDependencies(RealTimeClockController.class);
      }

      @Override
      public ClockInspectModel build(DependencyProvider dependencyProvider) {
        RealTimeClockController c = dependencyProvider
          .get(RealTimeClockController.class);
        return new ClockInspectModel(c);
      }
    }
  }

  static class TestRtSolver implements RealtimeSolver {
    Optional<Scheduler> scheduler = Optional.absent();

    @Override
    public void init(Scheduler s) {
      boolean fail = false;
      try {
        s.getCurrentSchedule();
      } catch (IllegalStateException e) {
        fail = true;
        assertThat(e.getMessage()).contains("No schedule has been set");
      }
      assertThat(fail).isTrue();
      scheduler = Optional.of(s);
    }

    @Override
    public void receiveSnapshot(GlobalStateObject snapshot) {
      assertThat(scheduler.isPresent()).isTrue();
      try {
        Thread.sleep(200);
      } catch (InterruptedException e) {
        throw new IllegalStateException(e);
      }
      scheduler.get().doneForNow();

      ImmutableList<ImmutableList<Parcel>> schedule = ImmutableList
        .of(ImmutableList.copyOf(snapshot.getAvailableParcels()),
          ImmutableList.<Parcel> of());

      scheduler.get().updateSchedule(schedule);
      assertThat(scheduler.get().getCurrentSchedule()).isEqualTo(schedule);
    }
  }
}