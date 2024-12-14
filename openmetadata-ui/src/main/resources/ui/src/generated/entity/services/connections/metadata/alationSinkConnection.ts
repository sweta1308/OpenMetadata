/*
 *  Copyright 2024 Collate.
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *  http://www.apache.org/licenses/LICENSE-2.0
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */


 /**
 * Alation Sink Connection Config
 */
export interface AlationSinkConnection {
    /**
     * Types of methods used to authenticate to the alation instance
     */
    authType:             AuthenticationTypeForAlation;
    connectionArguments?: { [key: string]: any };
    connectionOptions?:   { [key: string]: string };
    datasourceLinks?:     { [key: string]: string };
    /**
     * Host and port of the Alation service.
     */
    hostPort: string;
    /**
     * Pagination limit used for Alation APIs pagination
     */
    paginationLimit?: number;
    /**
     * Project name to create the refreshToken. Can be anything
     */
    projectName?:                string;
    sslConfig?:                  Config;
    supportsMetadataExtraction?: boolean;
    /**
     * Service Type
     */
    type?:      AlationSinkType;
    verifySSL?: VerifySSL;
}

/**
 * Types of methods used to authenticate to the alation instance
 *
 * Basic Auth Credentials
 *
 * API Access Token Auth Credentials
 */
export interface AuthenticationTypeForAlation {
    /**
     * Password to access the service.
     */
    password?: string;
    /**
     * Username to access the service.
     */
    username?: string;
    /**
     * Access Token for the API
     */
    accessToken?: string;
}

/**
 * Client SSL configuration
 *
 * OpenMetadata Client configured to validate SSL certificates.
 */
export interface Config {
    /**
     * The CA certificate used for SSL validation.
     */
    caCertificate?: string;
    /**
     * The SSL certificate used for client authentication.
     */
    sslCertificate?: string;
    /**
     * The private key associated with the SSL certificate.
     */
    sslKey?: string;
}

/**
 * Service Type
 *
 * Service type.
 */
export enum AlationSinkType {
    AlationSink = "AlationSink",
}

/**
 * Client SSL verification. Make sure to configure the SSLConfig if enabled.
 */
export enum VerifySSL {
    Ignore = "ignore",
    NoSSL = "no-ssl",
    Validate = "validate",
}