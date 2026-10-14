package org.buildmosaic.analysis.metadata

import org.buildmosaic.analysis.CallableContract
import org.buildmosaic.analysis.CanvasContract
import org.buildmosaic.analysis.DiscoveryKind
import org.buildmosaic.analysis.Effect
import org.buildmosaic.analysis.KeyContract
import org.buildmosaic.analysis.LookupKind
import org.buildmosaic.analysis.ModuleContract
import org.buildmosaic.analysis.MosaicProvenance
import org.buildmosaic.analysis.MosaicSuppression
import org.buildmosaic.analysis.MultiTileExecution
import org.buildmosaic.analysis.OverrideSlot
import org.buildmosaic.analysis.ResolvedOverride
import org.buildmosaic.analysis.TileContract

internal fun Effect.toWire(): WireEffect =
  when (this) {
    is Effect.Lookup -> WireEffect.Lookup(id, canvas.toWire(), key.toWire(), kind.name, site.toWire())
    is Effect.Compose ->
      WireEffect.Compose(
        id,
        canvas.toWire(),
        tile.toWire(),
        discovery.name,
        execution.name,
        site.toWire(),
        mosaic.toWire(),
      )
    is Effect.EstablishMosaic -> WireEffect.EstablishMosaic(id, canvas.toWire(), site.toWire())
    is Effect.ConstructCanvas -> WireEffect.ConstructCanvas(id, canvas.toWire(), site.toWire())
    is Effect.Call -> WireEffect.Call(id, target, arguments.toWire(), site.toWire(), receiver.toWire(), virtualDispatch)
    is Effect.Branch ->
      WireEffect.Branch(
        id,
        guard.toWire(),
        whenTrue.map {
          it.toWire()
        },
        whenFalse.map { it.toWire() },
        site.toWire(),
      )
    is Effect.Captured ->
      WireEffect.Captured(
        id,
        owner,
        origin.toWire(),
        effects.map {
          it.toWire()
        },
        faithfullyCaptured,
        site.toWire(),
      )
    is Effect.Unknown -> WireEffect.Unknown(id, reason, site.toWire())
  }

internal fun WireEffect.toModel(): Effect =
  when (this) {
    is WireEffect.Lookup ->
      Effect.Lookup(
        id,
        canvas.toModel(),
        key.toModel(),
        enumValue<LookupKind>(lookupKind),
        site.toModel(),
      )
    is WireEffect.Compose ->
      Effect.Compose(
        id,
        canvas.toModel(),
        tile.toModel(),
        enumValue<DiscoveryKind>(discovery),
        enumValue<MultiTileExecution>(execution),
        site.toModel(),
        mosaic.toModel(),
      )
    is WireEffect.EstablishMosaic -> Effect.EstablishMosaic(id, canvas.toModel(), site.toModel())
    is WireEffect.ConstructCanvas -> Effect.ConstructCanvas(id, canvas.toModel(), site.toModel())
    is WireEffect.Call ->
      Effect.Call(
        id,
        target,
        arguments.toModel(),
        site.toModel(),
        receiver.toModel(),
        virtualDispatch,
      )
    is WireEffect.Branch ->
      Effect.Branch(
        id,
        guard.toModel(),
        whenTrue.map {
          it.toModel()
        },
        whenFalse.map { it.toModel() },
        site.toModel(),
      )
    is WireEffect.Captured ->
      Effect.Captured(
        id,
        owner,
        origin.toModel(),
        effects.map {
          it.toModel()
        },
        faithfullyCaptured,
        site.toModel(),
      )
    is WireEffect.Unknown -> Effect.Unknown(id, reason, site.toModel())
  }

internal fun ModuleContract.toWire() =
  WireModule(
    id,
    canvases.sortedBy { it.id }.map {
      WireCanvasContract(
        it.id,
        it.parameters.map {
            p ->
          p.toWire()
        },
        it.result.toWire(),
        it.site.toWire(),
        it.reusable,
      )
    },
    tiles.sortedBy { it.id }.map {
      WireTileContract(
        it.id,
        it.effects.map {
            e ->
          e.toWire()
        },
        it.site.toWire(),
        it.reusable,
        it.multi,
        it.suppressions.map { suppression -> WireSuppression(suppression.ruleId, suppression.site.toWire()) },
      )
    },
    callables.sortedBy { it.id }.map {
      WireCallableContract(
        it.id,
        it.parameters.map {
            p ->
          p.toWire()
        },
        it.effects.map { e -> e.toWire() },
        it.site.toWire(),
        it.reusable,
      )
    },
    overrides.sortedWith(compareBy({ it.receiverType }, { it.baseId }, { it.implementationId })).map {
      WireOverride(
        it.receiverType,
        it.baseId,
        it.implementationId,
        it.slots.map {
            slot ->
          WireOverrideSlot(slot.base.toWire(), slot.implementation.toWire(), slot.position)
        },
      )
    },
    keys.sortedBy { it.id }.map { WireKeyContract(it.id, it.key.toWire(), it.site.toWire()) },
  )

internal fun WireModule.toModel() =
  ModuleContract(
    id,
    canvases.map {
      CanvasContract(
        it.id,
        it.parameters.map {
            p ->
          p.toModel()
        },
        it.result.toModel(),
        it.site.toModel(),
        it.reusable,
      )
    },
    tiles.map {
      TileContract(
        it.id,
        it.effects.map { e -> e.toModel() },
        it.site.toModel(),
        it.reusable,
        it.multi,
        it.suppressions.map { suppression -> MosaicSuppression(suppression.ruleId, suppression.site.toModel()) },
      )
    },
    callables.map {
      CallableContract(
        it.id,
        it.parameters.map {
            p ->
          p.toModel()
        },
        it.effects.map { e -> e.toModel() },
        it.site.toModel(),
        it.reusable,
      )
    },
    overrides.map {
      ResolvedOverride(
        it.receiverType,
        it.baseId,
        it.implementationId,
        it.slots.map {
            slot ->
          OverrideSlot(slot.base.toModel(), slot.implementation.toModel(), slot.position)
        },
      )
    },
    keys.map { KeyContract(it.id, it.key.toModel(), it.site.toModel()) },
  )

internal fun MosaicProvenance.toWire(): WireMosaic =
  when (this) {
    MosaicProvenance.Current -> WireMosaic.Current
    is MosaicProvenance.Established -> WireMosaic.Established(id)
    MosaicProvenance.Unknown -> WireMosaic.Unknown
  }

internal fun WireMosaic.toModel(): MosaicProvenance =
  when (this) {
    WireMosaic.Current -> MosaicProvenance.Current
    is WireMosaic.Established -> MosaicProvenance.Established(id)
    WireMosaic.Unknown -> MosaicProvenance.Unknown
  }
