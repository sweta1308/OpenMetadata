---
title: Ingest Owner from dbt
slug: /connectors/ingestion/workflows/dbt/ingest-dbt-owner
---

# Ingest Owner from dbt

Ingest the model/table owner information from dbt `manifest.json` or `catalog.json` file into openmetadata tables.

The owner can be a user or a team in OpenMetadata.

Follow the link [here](https://docs.getdbt.com/reference/resource-configs/meta) to add the owner to the dbt project's `schema.yml` file

## Requirements

### 1. Owner information in manifest.json file
Openmetadata fetches the owner information from the `manifest.json` file. Below is a sample `manifest.json` file node containing owner information under `node_name->metadata->owner`.

```json
"model.jaffle_shop.orders": {
			"metadata": {
				"type": "BASE TABLE",
				"schema": "dbt_jaffle",
				"name": "orders",
				"database": "dev",
				"comment": null,
				"owner": "openmetadata_team"
			}
}
```

### 2. Owner information in catalog.json file
Openmetadata fetches the owner information from the `catalog.json` file. Below is a sample `catalog.json` file node containing owner information under `node_name->metadata->owner`.

```json
"model.jaffle_shop.customers": {
      "metadata": {
        "type": "BASE TABLE",
        "schema": "dbt_jaffle",
        "name": "customers",
        "database": "dev",
        "comment": null,
        "owner": "openmetadata"
      },
}
```

### 3. Adding the User or Team to OpenMetadata
The user or team which will be set as the entity owner should be first created in OpenMetadata.

While linking the owner from `manifest.json` or `catalog.json` files to the entity, OpenMetadata first searches for the user if it is present. If the user is not present it searches for the team 

#### Following steps shows adding a User to OpenMetadata:
**1.** Click on the `Users` section from homepage

{% image
  src="/images/v1.8/features/ingestion/workflows/dbt/ingest_dbt_owner/click-users-page.png"
  alt="click-users-page"
  caption="Click Users page"
 /%}
{% image
  src="/images/v1.8/features/ingestion/workflows/dbt/ingest_dbt_owner/click-users-page1.png"
  alt="click-users-page"
  caption="Click Users page"
 /%}

**2.** Click on the `Add User` button

{% image
  src="/images/v1.8/features/ingestion/workflows/dbt/ingest_dbt_owner/click-add-user.png"
  alt="click-add-user"
  caption="Click Add User"
 /%}


3. Enter the details as shown for the user

{% note %}

If the owner's name in `manifest.json` or `catalog.json` file is `openmetadata`, you need to enter `openmetadata@youremail.com` in the email id section of add user form as shown below.

{% /note %}

{% image
  src="/images/v1.8/features/ingestion/workflows/dbt/ingest_dbt_owner/add-user-dbt.png"
  alt="add-user-dbt"
  caption="Add User"
 /%}


#### Following steps shows adding a Team to OpenMetadata:
**1.** Click on the `Teams` section from homepage

{% image
  src="/images/v1.8/features/ingestion/workflows/dbt/ingest_dbt_owner/click-teams-page.png"
  alt="click-teams-page"
  caption="Click Teams page"
 /%}
 {% image
  src="/images/v1.8/features/ingestion/workflows/dbt/ingest_dbt_owner/click-teams-page2.png"
  alt="click-teams-page"
  caption="Click Teams page"
 /%}

**2.** Click on the `Add Team` button

{% image
  src="/images/v1.8/features/ingestion/workflows/dbt/ingest_dbt_owner/click-add-team.png"
  alt="click-add-team"
  caption="Click Add Team"
 /%}


**3.** Enter the details as shown for the team

{% note %}

If the owner's name in `manifest.json` or `catalog.json` file is `openmetadata`, you need to enter `openmetadata` in the name section of add team form as shown below.

{% /note %}

{% image
  src="/images/v1.8/features/ingestion/workflows/dbt/ingest_dbt_owner/add-team-dbt.png"
  alt="add-team-dbt"
  caption="Add Team"
 /%}


## Linking the Owner to the table

After running the ingestion workflow with dbt you can see the created user or team getting linked to the table as it's owner as it was specified in the `manifest.json` or `catalog.json` file.

{% image
  src="/images/v1.8/features/ingestion/workflows/dbt/ingest_dbt_owner/linked-user.png"
  alt="linked-user"
  caption="Linked User"
 /%}



## Overriding the existing table Owners

To establish a unified and reliable system for owners, a single source of truth is necessary. It either is directly OpenMetadata, if individuals want to go there and keep updating, or if they prefer to keep it centralized in dbt, then we can always rely on that directly.

When the `Update Owners` toggle is enabled during the configuration of dbt ingestion, existing owners of tables will be overwritten with the dbt owners.

If toggle is disabled during the configuration of dbt ingestion, dbt owners will only be updated for tables in OpenMetadata that currently have no owners. Existing owners will remain unchanged and will not be overwritten with dbt owners.

{% image
  src="/images/v1.6/features/ingestion/workflows/dbt/dbt-features/dbt-update-owners.webp"
  alt="update-dbt-owners"
  caption="Update dbt Owners"
 /%}
