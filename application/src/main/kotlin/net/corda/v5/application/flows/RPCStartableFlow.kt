package net.corda.v5.application.flows

import net.corda.v5.base.annotations.Suspendable

/**
 * A flow that may be started via RPC.
 *
 * Flows initiated due to an RPC request receive a JSON encoded string in their call method, containing the body of the
 * RPC request that initiated this flow. The JSON marshalling service may be used to convert this input string into a
 * concrete object. The string return type is treated by the platform as a JSON encoded string to return to the RPC
 * service, and will appear in the RPC flow status when the flow completes. Again, this can be generated by serializing
 * via the JSON marshalling service.
 *
 * Flows implementing this interface must have a no-arg constructor. The flow invocation will fail if this constructor
 * does not exist.
 */
interface RPCStartableFlow : Flow {

    /**
     * The business logic for this flow should be written here.
     *
     * This is equivalent to the normal flow call method, where the output is fixed to being a JSON encoded string.
     * Additionally, the call method is invoked with the body of the RPC request.
     *
     * @param requestBody The body of the RPC request that started this flow, expressed as a JSON encoded string.
     * @return A JSON encoded string to be supplied to the flow status on flow completion as the result.
     */
    @Suspendable
    fun call(requestBody: RPCRequestData) : String
}