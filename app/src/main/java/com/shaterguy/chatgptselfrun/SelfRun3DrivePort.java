package com.shaterguy.chatgptselfrun;

interface SelfRun3DrivePort {
    SelfRun3Engine.State setup(String token, SelfRun3Engine.State original) throws Exception;
    SelfRun3Engine.State prepareTurn(String token, SelfRun3Engine.State original) throws Exception;
    SelfRun3DriveAdapter.ResultObservation observeResult(String token, SelfRun3Engine.State state) throws Exception;
}
